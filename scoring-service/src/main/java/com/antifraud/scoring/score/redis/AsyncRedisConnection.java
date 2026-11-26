package com.antifraud.scoring.score.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Component
public final class AsyncRedisConnection implements AutoCloseable {
    private final RedisURI address;
    private final RedisClient client;
    private CompletableFuture<StatefulRedisConnection<String, String>> connection;

    public AsyncRedisConnection(DataRedisProperties properties) {
        address = addressOf(properties);
        client = RedisClient.create(address);
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(address.getTimeout()).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }

    synchronized CompletionStage<RedisAsyncCommands<String, String>> commands() {
        if (connection == null || connection.isCompletedExceptionally()) {
            connection = client.connectAsync(StringCodec.UTF8, address).toCompletableFuture();
        }
        return connection.thenApply(StatefulRedisConnection::async);
    }

    private static RedisURI addressOf(DataRedisProperties props) {
        var timeout = props.getConnectTimeout() == null ? Duration.ofSeconds(1) : props.getConnectTimeout();
        if (props.getUrl() != null) {
            var uri = RedisURI.create(props.getUrl());
            uri.setTimeout(timeout);
            return uri;
        }
        var builder = RedisURI.Builder.redis(props.getHost(), props.getPort())
                .withDatabase(props.getDatabase()).withTimeout(timeout).withSsl(props.getSsl().isEnabled());
        if (props.getPassword() != null) {
            if (props.getUsername() == null) {
                builder.withPassword(props.getPassword().toCharArray());
            } else {
                builder.withAuthentication(props.getUsername(), props.getPassword().toCharArray());
            }
        }
        if (props.getClientName() != null) {
            builder.withClientName(props.getClientName());
        }
        return builder.build();
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
