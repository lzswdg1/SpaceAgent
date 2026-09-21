package com.spaceagent.platform.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sandbox cross-language contract compatibility guard.
 */
class PlatformContractCompatibilityTest {

    private static final Path FIXTURES = Path.of("../../contracts/fixtures");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sandboxFixturesMatchJavaModel() throws Exception {
        SandboxExecutionRequest request = objectMapper.readValue(
                read("sandbox/request.json"), SandboxExecutionRequest.class);
        SandboxExecutionResponse response = objectMapper.readValue(
                read("sandbox/response.json"), SandboxExecutionResponse.class);

        assertEquals("call-1", request.toolCallId());
        assertEquals("echo", request.command());
        assertFalse(request.resourcePolicy().allowNetwork());
        assertEquals(SandboxExecutionStatus.SUCCEEDED, response.status());
        assertEquals("hello\n", response.stdout());
        assertFalse(response.metadata().timedOut());
    }

    private String read(String relative) throws Exception {
        Path path = FIXTURES.resolve(relative);
        assertTrue(Files.isRegularFile(path), "missing contract fixture: " + path);
        return Files.readString(path);
    }
}
