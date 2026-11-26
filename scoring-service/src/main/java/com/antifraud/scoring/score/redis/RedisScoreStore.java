package com.antifraud.scoring.score.redis;

import com.antifraud.features.RedisUserTag;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.scoring.score.domain.Transaction;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Component
public final class RedisScoreStore {
    private static final String FEATURES = readFeatureScript();
    private static final String FEATURES_SHA = digest(FEATURES);
    // Ключи резервирования и вердикта находятся в слоте txId, а Lua-окна — в слоте пользователя.
    private static final String RESERVE = """
            local response = redis.call('GET', KEYS[1])
            if response then return {'replayed', response} end
            local user = redis.call('GET', KEYS[2])
            if user and user ~= ARGV[1] then return {'pending'} end
            if redis.call('SET', KEYS[3], ARGV[2], 'NX', 'PX', 1000) then
                if not user then redis.call('SET', KEYS[2], ARGV[1], 'EX', 86400) end
                return {'reserved'}
            end
            return {'pending'}
            """;
    private static final String COMPLETE = """
            local response = redis.call('GET', KEYS[1])
            if response then return response end
            if redis.call('GET', KEYS[3]) ~= ARGV[1] then return false end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', 86400)
            redis.call('DEL', KEYS[2], KEYS[3])
            return ''
            """;

    private final AsyncRedisConnection connection;
    private final CircuitBreaker breaker;
    private final long deadlineNs;

    public RedisScoreStore(AsyncRedisConnection connection, RedisScoringProperties properties) {
        this.connection = connection;
        deadlineNs = properties.deadline().toNanos();
        breaker = CircuitBreaker.of("scoring-redis", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(80)
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(RedisScoreStore::isRedisUnavailable)
                .build());
        connection.commands().thenCompose(commands -> commands.scriptLoad(FEATURES));
    }

    public CompletionStage<ScoreResponse> score(
            Transaction tx, Function<Optional<RedisFeatureSnapshot>, CompletionStage<ScoreResponse>> calculate
    ) {
        var deadline = new Deadline(System.nanoTime() + deadlineNs);
        return breaker.executeCompletionStage(() -> {
            var userTag = RedisUserTag.of(tx.userId().value());
            var keys = idempotencyKeys(tx.id());
            var token = UUID.randomUUID().toString();
            var featureRead = connection.commands().thenCompose(commands ->
                    reserve(commands, tx, userTag, keys, token, deadline));
            return withinDeadline(featureRead, deadline).thenCompose(reservation -> switch (reservation) {
                case Replayed cached -> CompletableFuture.completedFuture(cached.response());
                case Reserved fresh -> {
                    deadline.check();
                    var remaining = deadline.remainingNs();
                    // Очередь и инференс не расходуют остаток бюджета Redis.
                    yield calculate.apply(fresh.snapshot()).thenCompose(response -> {
                        var saveDeadline = new Deadline(System.nanoTime() + remaining);
                        return withinDeadline(complete(fresh.commands(), keys, token, response), saveDeadline)
                                .exceptionallyCompose(error -> CompletableFuture.failedFuture(isRedisUnavailable(error)
                                        ? new CacheWriteFailure(response, unwrap(error)) : error));
                    });
                }
            });
        }).exceptionallyCompose(error -> {
            if (unwrap(error) instanceof CacheWriteFailure failure) {
                // Команда записи ещё может завершиться. Клиент и кэш должны получить один вердикт.
                return CompletableFuture.completedFuture(failure.response);
            }
            return CompletableFuture.failedFuture(error);
        });
    }

    private static <T> CompletableFuture<T> withinDeadline(CompletionStage<T> operation, Deadline deadline) {
        return operation.toCompletableFuture().orTimeout(deadline.remainingNs(), TimeUnit.NANOSECONDS);
    }

    private CompletionStage<Reservation> reserve(
            RedisAsyncCommands<String, String> commands, Transaction tx, String userTag, String[] keys,
            String token, Deadline deadline
    ) {
        deadline.check();
        return commands.<List<String>>eval(RESERVE, ScriptOutputType.MULTI, keys, userTag, token)
                .thenCompose(result -> switch (result.getFirst()) {
                    case "replayed" -> CompletableFuture.completedFuture(new Replayed(replayed(result.get(1))));
                    case "reserved" -> updateFeatures(commands, tx, userTag, deadline)
                            .thenApply(snapshot -> new Reserved(commands, snapshot));
                    case "pending" -> CompletableFuture.completedFuture(null)
                            .thenComposeAsync(ignored -> reserve(commands, tx, userTag, keys, token, deadline),
                                    CompletableFuture.delayedExecutor(1, TimeUnit.MILLISECONDS));
                    default -> CompletableFuture.failedFuture(new IllegalStateException("Неизвестный статус Redis"));
                });
    }

    private CompletionStage<Optional<RedisFeatureSnapshot>> updateFeatures(
            RedisAsyncCommands<String, String> commands, Transaction tx, String tag, Deadline deadline
    ) {
        deadline.check();
        String[] keys = {"win:" + tag, "small:" + tag, "last:" + tag, "prof:" + tag,
                "dev:" + tag, "ip:" + tag, "cty:" + tag, "feature-seen:" + tag + ":" + tx.id()};
        String[] args = {tx.id().toString(), Long.toString(tx.timestamp().toEpochMilli()),
                Long.toString(tx.money().amountMinor()), tx.merchantId(), tx.deviceId(), tx.ip(), tx.country(),
                tx.latitude().isPresent() ? Double.toString(tx.latitude().getAsDouble()) : "",
                tx.longitude().isPresent() ? Double.toString(tx.longitude().getAsDouble()) : ""};
        return commands.<List<Object>>evalsha(FEATURES_SHA, ScriptOutputType.MULTI, keys, args)
                .exceptionallyCompose(error -> {
                    if (!(unwrap(error) instanceof RedisNoScriptException)) {
                        return CompletableFuture.failedFuture(error);
                    }
                    deadline.check();
                    return commands.scriptLoad(FEATURES).thenCompose(sha -> {
                        deadline.check();
                        return commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
                    });
                })
                .thenApply(result -> (long) result.getFirst() == 0
                        ? Optional.empty() : Optional.of(RedisFeatureSnapshot.decode(result, tx)));
    }

    private CompletionStage<ScoreResponse> complete(
            RedisAsyncCommands<String, String> commands, String[] keys, String token,
            ScoreResponse response
    ) {
        var payload = Base64.getEncoder().encodeToString(response.toByteArray());
        return commands.<String>eval(COMPLETE, ScriptOutputType.VALUE, keys, token, payload)
                .thenApply(existing -> {
                    if (existing == null) {
                        throw new CompletionException(new TimeoutException("Истекла аренда идентификатора транзакции"));
                    }
                    return existing.isEmpty() ? response : replayed(existing);
                });
    }

    private static ScoreResponse replayed(String payload) {
        try {
            return ScoreResponse.parseFrom(Base64.getDecoder().decode(payload)).toBuilder().setReplayed(true).build();
        } catch (IllegalArgumentException | InvalidProtocolBufferException exception) {
            throw new IllegalStateException("Повреждён сохранённый ответ скоринга", exception);
        }
    }

    private static String[] idempotencyKeys(UUID transactionId) {
        var tag = "{" + transactionId + "}";
        return new String[]{"idem:" + tag, "idem-user:" + tag, "idem-lease:" + tag};
    }

    public static boolean isRedisUnavailable(Throwable error) {
        return switch (unwrap(error)) {
            case RedisException ignored -> true;
            case TimeoutException ignored -> true;
            case CallNotPermittedException ignored -> true;
            case CacheWriteFailure ignored -> true;
            default -> false;
        };
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static String readFeatureScript() {
        try (var resource = RedisScoreStore.class.getResourceAsStream("/redis/update-features.lua")) {
            if (resource == null) {
                throw new IOException("Не найден redis/update-features.lua");
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String digest(String script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 недоступен", exception);
        }
    }

    private static final class CacheWriteFailure extends RuntimeException {
        private final ScoreResponse response;

        private CacheWriteFailure(ScoreResponse response, Throwable cause) {
            super(cause);
            this.response = response;
        }
    }

    private sealed interface Reservation permits Replayed, Reserved {}

    private record Replayed(ScoreResponse response) implements Reservation {}

    private record Reserved(RedisAsyncCommands<String, String> commands, Optional<RedisFeatureSnapshot> snapshot)
            implements Reservation {}

    private record Deadline(long expiresAt) {
        private long remainingNs() {
            return Math.max(0, expiresAt - System.nanoTime());
        }

        private void check() {
            if (remainingNs() == 0) {
                throw new CompletionException(new TimeoutException("Истёк дедлайн Redis"));
            }
        }
    }
}
