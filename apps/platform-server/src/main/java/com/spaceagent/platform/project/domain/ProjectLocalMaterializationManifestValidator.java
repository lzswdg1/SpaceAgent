package com.spaceagent.platform.project.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Pure validation of untrusted Local Bridge snapshot metadata. It never reads bytes or creates a Source. */
public final class ProjectLocalMaterializationManifestValidator {
    public static final int MAX_FILE_COUNT = 100_000;
    public static final long MAX_TOTAL_BYTES = 1L << 30;

    private ProjectLocalMaterializationManifestValidator() { }

    public static ValidatedManifest validate(
            Manifest manifest, List<ProjectLocalMaterializationChunk> chunks) {
        Objects.requireNonNull(manifest, "manifest");
        List<Entry> entries = List.copyOf(Objects.requireNonNull(manifest.entries(), "entries"));
        if (entries.size() > MAX_FILE_COUNT || manifest.fileCount() != entries.size()
                || manifest.totalBytes() < 0 || manifest.totalBytes() > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("manifest bounds are invalid");
        }
        long total = 0;
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (Entry entry : entries) {
            validatePath(entry.relativePath());
            if (entry.kind() != EntryKind.REGULAR_FILE) {
                throw new IllegalArgumentException("manifest contains unsupported entry kind");
            }
            if (entry.contentLength() < 0 || entry.contentLength() > MAX_TOTAL_BYTES
                    || !paths.add(entry.relativePath())) {
                throw new IllegalArgumentException("manifest entry is invalid");
            }
            requireHash(entry.contentSha256(), "entry content hash");
            total = Math.addExact(total, entry.contentLength());
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("manifest total exceeds limit");
            }
        }
        if (total != manifest.totalBytes()) {
            throw new IllegalArgumentException("manifest total does not match entries");
        }
        String computedDigest = canonicalDigest(entries);
        if (!computedDigest.equals(manifest.manifestSha256())) {
            throw new IllegalArgumentException("manifest digest does not match canonical entries");
        }
        Map<String, List<ProjectLocalMaterializationChunk>> chunksByPath = List.copyOf(chunks).stream()
                .collect(Collectors.groupingBy(ProjectLocalMaterializationChunk::relativePath));
        for (Entry entry : entries) {
            validateCoverage(entry, chunksByPath.remove(entry.relativePath()));
        }
        if (!chunksByPath.isEmpty()) {
            throw new IllegalArgumentException("chunk path is absent from manifest");
        }
        return new ValidatedManifest(manifest.manifestSha256(), manifest.fileCount(), manifest.totalBytes());
    }

    public static String canonicalDigest(List<Entry> entries) {
        List<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(Entry::relativePath));
        StringBuilder canonical = new StringBuilder();
        for (Entry entry : ordered) {
            canonical.append(entry.relativePath()).append('\0').append(entry.kind()).append('\0')
                    .append(entry.contentLength()).append('\0').append(entry.contentSha256()).append('\n');
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void validateCoverage(Entry entry, List<ProjectLocalMaterializationChunk> chunks) {
        List<ProjectLocalMaterializationChunk> ordered = chunks == null ? List.of() : chunks.stream()
                .sorted(Comparator.comparingLong(ProjectLocalMaterializationChunk::offset)
                        .thenComparing(ProjectLocalMaterializationChunk::requestId)).toList();
        long expectedOffset = 0;
        for (ProjectLocalMaterializationChunk chunk : ordered) {
            if (chunk.state() != ProjectLocalMaterializationChunkState.STORED
                    || chunk.offset() != expectedOffset) {
                throw new IllegalArgumentException("chunk coverage is not contiguous and stored");
            }
            expectedOffset = Math.addExact(expectedOffset, chunk.contentLength());
            if (expectedOffset > entry.contentLength()) {
                throw new IllegalArgumentException("chunk coverage exceeds file length");
            }
        }
        if (expectedOffset != entry.contentLength()) {
            throw new IllegalArgumentException("chunk coverage is incomplete");
        }
    }

    private static void validatePath(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.indexOf('\0') >= 0
                || value.startsWith("/") || value.startsWith("\\") || value.matches("^[A-Za-z]:.*")
                || value.contains("\\")) {
            throw new IllegalArgumentException("manifest path is not a safe relative POSIX path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("manifest path contains an unsafe segment");
            }
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    public enum EntryKind { REGULAR_FILE, DIRECTORY, SYMLINK, SUBMODULE, SPECIAL }

    public record Entry(String relativePath, EntryKind kind, long contentLength, String contentSha256) {
        public Entry {
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    public record Manifest(String manifestSha256, int fileCount, long totalBytes, List<Entry> entries) {
        public Manifest {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    public record ValidatedManifest(String manifestSha256, int fileCount, long totalBytes) { }
}
