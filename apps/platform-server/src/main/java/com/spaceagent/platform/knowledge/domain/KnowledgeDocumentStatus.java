package com.spaceagent.platform.knowledge.domain;

/**
 * Ingestion lifecycle state of a knowledge document.
 */
public enum KnowledgeDocumentStatus {
    UPLOADED,
    PROCESSING,
    READY,
    FAILED,
    DELETED
}
