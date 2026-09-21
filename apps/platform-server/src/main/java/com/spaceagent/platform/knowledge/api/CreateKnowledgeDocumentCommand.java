package com.spaceagent.platform.knowledge.api;

/**
 * Public command for registering a knowledge document.
 */
public record CreateKnowledgeDocumentCommand(
        String ownerId,
        String name,
        String contentType,
        String storageLocation) {

    public CreateKnowledgeDocumentCommand {
        requireNonBlank(ownerId, "ownerId");
        requireNonBlank(name, "name");
        requireNonBlank(contentType, "contentType");
        requireNonBlank(storageLocation, "storageLocation");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
