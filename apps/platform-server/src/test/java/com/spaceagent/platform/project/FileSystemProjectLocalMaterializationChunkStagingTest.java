package com.spaceagent.platform.project;

import com.spaceagent.platform.project.infrastructure.FileSystemProjectLocalMaterializationChunkStaging;
import com.spaceagent.platform.project.infrastructure.WorkspaceProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemProjectLocalMaterializationChunkStagingTest {
    @TempDir
    Path temporaryRoot;

    @Test
    void stagesAndReplaysOnlyAtServerDerivedSessionKey() throws Exception {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setManagedRoot(temporaryRoot.resolve("managed").toString());
        var staging = new FileSystemProjectLocalMaterializationChunkStaging(properties);
        String sessionId = UUID.randomUUID().toString();
        String key = "a".repeat(64);
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(body);

        assertThat(staging.stage(sessionId, key, body.length, hash, new ByteArrayInputStream(body)).stagingKey())
                .isEqualTo(key);
        assertThat(staging.stage(sessionId, key, body.length, hash, new ByteArrayInputStream(body)).contentLength())
                .isEqualTo((long) body.length);
        try (var input = staging.open(sessionId, key)) {
            assertThat(input.readAllBytes()).isEqualTo(body);
        }
        assertThat(temporaryRoot.resolve("managed/local-materialization").resolve(sessionId).resolve(key)).exists();
    }

    @Test
    void rejectsMismatchedBodyAndNeverUsesClientMetadataAsPath() throws Exception {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setManagedRoot(temporaryRoot.resolve("managed").toString());
        var staging = new FileSystemProjectLocalMaterializationChunkStaging(properties);
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String sessionId = UUID.randomUUID().toString();
        String key = "b".repeat(64);
        assertThatThrownBy(() -> staging.stage(sessionId, key, body.length,
                sha256("other".getBytes(StandardCharsets.UTF_8)), new ByteArrayInputStream(body)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> staging.open("../../outside", key))
                .isInstanceOf(IllegalArgumentException.class);
        Path sessionRoot = temporaryRoot.resolve("managed/local-materialization").resolve(sessionId);
        assertThat(sessionRoot.resolve(key)).doesNotExist();
        try (var files = Files.list(sessionRoot)) {
            assertThat(files).isEmpty();
        }
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
