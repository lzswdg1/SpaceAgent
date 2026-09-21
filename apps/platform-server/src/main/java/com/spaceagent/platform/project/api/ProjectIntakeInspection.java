package com.spaceagent.platform.project.api;

import java.util.List;

/** Bounded and redacted source evidence used only to generate an intake proposal. */
public record ProjectIntakeInspection(
        String headCommit,
        int trackedFileCount,
        List<String> trackedPaths,
        List<InspectedFile> selectedFiles) {

    public ProjectIntakeInspection {
        if (headCommit == null || !headCommit.matches("[0-9a-f]{40,64}")
                || trackedFileCount < 0 || trackedFileCount > 20_000) {
            throw new IllegalArgumentException("Project intake inspection is invalid");
        }
        trackedPaths = trackedPaths == null ? List.of() : List.copyOf(trackedPaths);
        selectedFiles = selectedFiles == null ? List.of() : List.copyOf(selectedFiles);
        if (trackedPaths.size() > 1_000 || trackedPaths.stream().anyMatch(path ->
                path == null || path.isBlank() || path.length() > 500
                        || path.startsWith("/") || path.contains(".."))) {
            throw new IllegalArgumentException("Project intake tracked paths are invalid");
        }
        if (selectedFiles.size() > 24) {
            throw new IllegalArgumentException("Project intake selected-file limit exceeded");
        }
    }

    public record InspectedFile(
            String path,
            String sha256,
            boolean truncated,
            String redactedContent) {
        public InspectedFile {
            if (path == null || path.isBlank() || path.length() > 500
                    || path.startsWith("/") || path.contains("..")
                    || sha256 == null || !sha256.matches("sha256:[0-9a-f]{64}")
                    || redactedContent == null || redactedContent.length() > 32_000) {
                throw new IllegalArgumentException("Inspected Project file is invalid");
            }
        }
    }
}
