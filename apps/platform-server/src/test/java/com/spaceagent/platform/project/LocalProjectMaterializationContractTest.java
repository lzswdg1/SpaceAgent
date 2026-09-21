package com.spaceagent.platform.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.LocalProjectMaterializationContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalProjectMaterializationContractTest {
    private static final String HASH = "sha256:" + "a".repeat(64);

    @Test
    void acceptsPathFreeVersionedMessageShapes() {
        var start = new LocalProjectMaterializationContract.StartSessionRequest(
                LocalProjectMaterializationContract.VERSION, "request-1", "tenant-1", "owner-1",
                "project-1", "bridge-1");
        var entry = new LocalProjectMaterializationContract.FileEntry("src/App.java", 5, HASH);
        var manifest = new LocalProjectMaterializationContract.ManifestDeclaration(
                LocalProjectMaterializationContract.VERSION, "session-1", "request-2", HASH, 5, 1,
                List.of(entry));
        var chunk = new LocalProjectMaterializationContract.ChunkDescriptor(
                LocalProjectMaterializationContract.VERSION, "session-1", "request-3", "src/App.java",
                0, 5, HASH);
        var reference = new LocalProjectMaterializationContract.SessionReference(
                LocalProjectMaterializationContract.VERSION, "session-1", "bridge-1", "device-1",
                Instant.parse("2026-09-08T12:00:00Z"));

        assertThat(start.bridgeId()).isEqualTo(reference.bridgeId());
        assertThat(manifest.entries()).containsExactly(entry);
        assertThat(chunk.contentLength()).isEqualTo(5);
    }

    @Test
    void rejectsUnsupportedVersionInvalidDigestAndDuplicateManifestEntry() {
        assertThatThrownBy(() -> new LocalProjectMaterializationContract.StartSessionRequest(
                "local-materialization/v2", "request-1", "tenant-1", "owner-1", "project-1", "bridge-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProjectMaterializationContract.FileEntry("file", 1, "sha256:bad"))
                .isInstanceOf(IllegalArgumentException.class);
        var entry = new LocalProjectMaterializationContract.FileEntry("file", 1, HASH);
        assertThatThrownBy(() -> new LocalProjectMaterializationContract.ManifestDeclaration(
                LocalProjectMaterializationContract.VERSION, "session-1", "request-1", HASH, 2, 2,
                List.of(entry, entry))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidChunkRangesAndIncompleteManifestShape() {
        assertThatThrownBy(() -> new LocalProjectMaterializationContract.ChunkDescriptor(
                LocalProjectMaterializationContract.VERSION, "session-1", "request-1", "file", -1, 1, HASH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalProjectMaterializationContract.ManifestDeclaration(
                LocalProjectMaterializationContract.VERSION, "session-1", "request-1", HASH, 1, 2,
                List.of(new LocalProjectMaterializationContract.FileEntry("file", 1, HASH))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schemasAreStrictVersionedAndExcludePathOrCredentialFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String name : List.of("materialization-session", "materialization-manifest", "materialization-chunk", "materialization-finalize")) {
            JsonNode schema = mapper.readTree(Files.readString(repositoryFile(
                    "contracts/local-bridge/v1/" + name + ".schema.json")));
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("properties").path("contractVersion").path("const").asText())
                    .isEqualTo(LocalProjectMaterializationContract.VERSION);
            assertThat(schema.toString()).doesNotContain("rootHandle", "bridgeToken", "absolutePath");
        }
    }

    private static Path repositoryFile(String relative) {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            Path file = candidate.resolve(relative);
            if (Files.exists(file)) {
                return file;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Repository file is missing: " + relative);
    }
}
