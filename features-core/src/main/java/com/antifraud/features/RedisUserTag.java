package com.antifraud.features;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class RedisUserTag {
    private RedisUserTag() {}

    public static String of(String userId) {
        if (userId.contains("{") || userId.contains("}") || userId.startsWith("~")) {
            userId = "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(userId.getBytes(StandardCharsets.UTF_8));
        }
        return "{" + userId + "}";
    }
}
