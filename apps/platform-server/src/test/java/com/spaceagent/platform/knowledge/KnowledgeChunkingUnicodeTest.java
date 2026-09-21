package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.application.KnowledgeChunkingService;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkingUnicodeTest {
    @Test void chunkAndOverlapBoundariesKeepSupplementaryCodePointsIntact() {
        String text="a".repeat(50)+"😀"+"b".repeat(47)+"😀"+"c".repeat(200);
        var chunks=new KnowledgeChunkingService(100,50).chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(c->new String(c.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8).equals(c));
        assertThat(chunks.get(0)).endsWith("😀");assertThat(chunks.get(1)).startsWith("😀");
    }
}
