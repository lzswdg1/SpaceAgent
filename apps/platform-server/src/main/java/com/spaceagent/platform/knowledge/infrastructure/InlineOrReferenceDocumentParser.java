package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.KnowledgeDocument;
import com.spaceagent.platform.knowledge.domain.KnowledgeDocumentParser;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Parsing adapter for supplied text and inline storage references. Object-store/file
 * transports can replace this adapter without changing Knowledge application logic.
 */
@Component
public class InlineOrReferenceDocumentParser implements KnowledgeDocumentParser {

    @Override
    public String parse(KnowledgeDocument document, String suppliedContent) {
        if (suppliedContent != null && !suppliedContent.isBlank()) {
            return suppliedContent;
        }
        if (document.storageLocation().startsWith("inline:")) {
            return document.storageLocation().substring("inline:".length());
        }
        throw new BusinessException(
                "Document content is unavailable for storage reference " + document.storageLocation(),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "KNOWLEDGE_PARSER_CONTENT_UNAVAILABLE");
    }
}
