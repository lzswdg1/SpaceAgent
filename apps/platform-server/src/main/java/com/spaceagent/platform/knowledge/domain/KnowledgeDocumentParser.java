package com.spaceagent.platform.knowledge.domain;

/**
 * Parsing boundary for inline content or an external storage reference.
 */
public interface KnowledgeDocumentParser {
    String parse(KnowledgeDocument document, String suppliedContent);
}
