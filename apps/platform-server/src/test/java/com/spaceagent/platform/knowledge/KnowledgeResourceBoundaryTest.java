package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.api.AddKnowledgeChunkCommand;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.api.ProcessKnowledgeDocumentCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeResourceBoundaryTest {
    @Test
    void commandsRejectOversizedContentAndFanout() {
        assertThatThrownBy(() -> new ProcessKnowledgeDocumentCommand(
                "document", "owner", "x".repeat(1_000_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AddKnowledgeChunkCommand(
                "document", 512, "content", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AddKnowledgeChunkCommand(
                "document", 0, "x".repeat(20_001), null))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> ids = IntStream.range(0, 65).mapToObj(index -> "doc-" + index).toList();
        assertThatThrownBy(() -> new KnowledgeRetrievalCommand(
                "owner", ids, "query", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
