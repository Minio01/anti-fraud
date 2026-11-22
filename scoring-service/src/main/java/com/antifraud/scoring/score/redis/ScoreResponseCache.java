package com.antifraud.scoring.score.redis;

import com.antifraud.proto.v1.ScoreResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public final class ScoreResponseCache {
    private static final String TTL_SECONDS = Long.toString(Duration.ofHours(24).toSeconds());
    private static final RedisScript<String> SAVE_IF_ABSENT = RedisScript.of(
            "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2], 'GET')", String.class);

    private final StringRedisTemplate redis;

    public ScoreResponseCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public ScoreResponse find(UUID transactionId) {
        return decode(redis.opsForValue().get(key(transactionId)));
    }

    public ScoreResponse saveIfAbsent(UUID transactionId, ScoreResponse response) {
        String payload = Base64.getEncoder().encodeToString(response.toByteArray());
        String existing = redis.execute(SAVE_IF_ABSENT, List.of(key(transactionId)), payload, TTL_SECONDS);
        return decode(existing);
    }

    private static String key(UUID transactionId) {
        return "idem:{" + transactionId + "}";
    }

    private static ScoreResponse decode(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return ScoreResponse.parseFrom(Base64.getDecoder().decode(payload));
        } catch (IllegalArgumentException | InvalidProtocolBufferException exception) {
            throw new IllegalStateException("Повреждён сохранённый ответ скоринга", exception);
        }
    }
}
