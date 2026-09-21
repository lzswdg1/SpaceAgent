package com.spaceagent.platform.project;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationManifestValidator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectLocalMaterializationManifestValidatorTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String HASH_A = "sha256:" + "a".repeat(64);

    @Test
    void acceptsCanonicalRegularFilesWithExactChunkCoverage() {
        var entry = entry("src/App.java", 5);
        var manifest = manifest(List.of(entry));
        var chunk = chunk("src/App.java", 0, 5, "request-1");
        assertThat(ProjectLocalMaterializationManifestValidator.validate(manifest, List.of(chunk)))
                .extracting(ProjectLocalMaterializationManifestValidator.ValidatedManifest::totalBytes)
                .isEqualTo(5L);
    }

    @Test
    void rejectsTraversalAbsoluteDriveBackslashAndUnsafeEntryKinds() {
        for (String path : List.of("/absolute", "../escape", "src/../escape", "C:\\drive", "src\\file", "src//file", "./file")) {
            var entry = entry(path, 1);
            var manifest = manifest(List.of(entry));
            assertThatThrownBy(() -> ProjectLocalMaterializationManifestValidator.validate(manifest, List.of(chunk(path, 0, 1, "request"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (var kind : List.of(ProjectLocalMaterializationManifestValidator.EntryKind.SYMLINK,
                ProjectLocalMaterializationManifestValidator.EntryKind.SUBMODULE,
                ProjectLocalMaterializationManifestValidator.EntryKind.SPECIAL)) {
            var entry = new ProjectLocalMaterializationManifestValidator.Entry("safe", kind, 1, HASH_A);
            var manifest = new ProjectLocalMaterializationManifestValidator.Manifest(
                    ProjectLocalMaterializationManifestValidator.canonicalDigest(List.of(entry)), 1, 1, List.of(entry));
            assertThatThrownBy(() -> ProjectLocalMaterializationManifestValidator.validate(manifest, List.of(chunk("safe", 0, 1, "request"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDigestMismatchOversizeAndIncompleteOrOverlappingChunks() {
        var entry = entry("safe", 5);
        var valid = manifest(List.of(entry));
        var badDigest = new ProjectLocalMaterializationManifestValidator.Manifest(HASH_A, 1, 5, List.of(entry));
        assertThatThrownBy(() -> ProjectLocalMaterializationManifestValidator.validate(badDigest, List.of(chunk("safe", 0, 5, "one"))))
                .isInstanceOf(IllegalArgumentException.class);
        var oversized = entry("oversized", ProjectLocalMaterializationManifestValidator.MAX_TOTAL_BYTES + 1);
        var oversizedManifest = new ProjectLocalMaterializationManifestValidator.Manifest(
                ProjectLocalMaterializationManifestValidator.canonicalDigest(List.of(oversized)), 1,
                ProjectLocalMaterializationManifestValidator.MAX_TOTAL_BYTES + 1, List.of(oversized));
        assertThatThrownBy(() -> ProjectLocalMaterializationManifestValidator.validate(oversizedManifest, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectLocalMaterializationManifestValidator.validate(valid, List.of(chunk("safe", 1, 4, "gap"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectLocalMaterializationManifestValidator.validate(valid, List.of(
                chunk("safe", 0, 3, "one"), chunk("safe", 2, 3, "two"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProjectLocalMaterializationManifestValidator.Entry entry(String path, long size) {
        return new ProjectLocalMaterializationManifestValidator.Entry(
                path, ProjectLocalMaterializationManifestValidator.EntryKind.REGULAR_FILE, size, HASH_A);
    }

    private static ProjectLocalMaterializationManifestValidator.Manifest manifest(
            List<ProjectLocalMaterializationManifestValidator.Entry> entries) {
        long total = entries.stream().mapToLong(ProjectLocalMaterializationManifestValidator.Entry::contentLength).sum();
        return new ProjectLocalMaterializationManifestValidator.Manifest(
                ProjectLocalMaterializationManifestValidator.canonicalDigest(entries), entries.size(), total, entries);
    }

    private static ProjectLocalMaterializationChunk chunk(String path, long offset, long length, String requestId) {
        return ProjectLocalMaterializationChunk.stored(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                requestId, path, offset, length, HASH_A, "b".repeat(64), NOW);
    }
}
