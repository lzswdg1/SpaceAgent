package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class GraphV2CommandHasher {
    private GraphV2CommandHasher() {}

    public static String hash(
            ObjectMapper mapper, String graphSessionId, long currentCursorSequence,
            GraphV2Contract.Kind kind, Map<String, Object> payload) {
        try {
            ObjectMapper canonical = mapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            String body = graphSessionId + "\n" + currentCursorSequence + "\n"
                    + kind.name() + "\n" + canonical.writeValueAsString(payload);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash graph command", exception);
        }
    }

    public static String payload(ObjectMapper mapper, Map<String, Object> payload) {
        try {
            return mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode graph command payload", exception);
        }
    }
}
