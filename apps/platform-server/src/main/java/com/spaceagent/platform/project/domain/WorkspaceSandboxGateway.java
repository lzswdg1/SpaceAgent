package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Map;

/** Project-owned typed port; Integration adapts it to Tooling's disposable OCI compute API. */
public interface WorkspaceSandboxGateway {
    long MAX_PREPARED_BUNDLE_BYTES = 16L * 1024 * 1024;

    FileRead readFile(Workspace workspace, Execution execution, String path, int maxCharacters);
    FileList listFiles(Workspace workspace, Execution execution, String path, int maxDepth, int maxEntries);
    Snapshot snapshot(Workspace workspace, Execution execution, int maxCharacters);
    DocumentRead readDocument(Workspace workspace, Execution execution, String path, int maxCharacters);
    DocumentWrite writeDocument(Workspace workspace, Execution execution, String path, String content, String format);
    CodingResult execute(Workspace workspace, Execution execution, WorkspaceCodingGateway.CodingOperation operation);
    PreparedCommit prepareCommit(Workspace workspace, Execution execution, String expectedBase,
                                 String patchHash, String commitMessage);

    record Execution(String agentRunId, String toolCallId) {
        public Execution {
            if (agentRunId == null || agentRunId.isBlank()
                    || toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("Sandbox execution correlation is required");
            }
        }
    }
    record FileRead(String path, String content, long sizeBytes, boolean truncated) {}
    record Entry(String path, boolean directory, long sizeBytes) {}
    record FileList(String root, List<Entry> entries, boolean truncated) {
        public FileList { entries = entries == null ? List.of() : List.copyOf(entries); }
    }
    record Snapshot(String headCommit, String status, String patch,
                    List<String> changedFiles, boolean truncated) {
        public Snapshot { changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles); }
    }
    record DocumentRead(String path, String mediaType, String content,
                        Map<String, String> metadata, boolean truncated) {
        public DocumentRead { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record DocumentWrite(String path, String format, long sizeBytes, List<String> changedFiles) {
        public DocumentWrite { changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles); }
    }
    record CodingResult(int exitCode, String stdout, String stderr,
                        List<String> changedFiles, String safeErrorCode, boolean ambiguous) {
        public CodingResult { changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles); }
    }
    record PreparedCommit(
            String commit,
            String bundleReference,
            String bundleSha256,
            long bundleSizeBytes,
            byte[] bundleBytes) {
        public PreparedCommit {
            bundleBytes = bundleBytes == null ? null : bundleBytes.clone();
            if (commit == null || !commit.matches("[0-9a-f]{40,64}")
                    || !expectedBundleReference(commit).equals(bundleReference)
                    || bundleSha256 == null
                    || !bundleSha256.matches("sha256:[0-9a-f]{64}")
                    || bundleSizeBytes < 1 || bundleSizeBytes > MAX_PREPARED_BUNDLE_BYTES
                    || bundleBytes == null || bundleSizeBytes != bundleBytes.length) {
                throw new IllegalArgumentException("Prepared Git bundle evidence is invalid");
            }
        }

        @Override
        public byte[] bundleBytes() {
            return bundleBytes.clone();
        }

        private static String expectedBundleReference(String commit) {
            return ".git/spaceagent-transfer/" + commit + ".bundle";
        }
    }
}
