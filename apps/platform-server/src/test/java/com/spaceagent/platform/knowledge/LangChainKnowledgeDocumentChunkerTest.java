package com.spaceagent.platform.knowledge;
import com.spaceagent.platform.knowledge.domain.*;
import com.spaceagent.platform.knowledge.infrastructure.LangChainKnowledgeDocumentChunker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class LangChainKnowledgeDocumentChunkerTest {
    private final LangChainKnowledgeDocumentChunker chunks=new LangChainKnowledgeDocumentChunker();
    @Test void recursiveTokensHandleChineseAndLongTextWithinBounds(){
        var p=new KnowledgeProcessingPolicy(KnowledgeProcessingPolicy.Strategy.RECURSIVE_TOKEN,128,16);
        var result=chunks.split("知识库需要权限隔离与持久化。中文测试和 English sentences.\n\n".repeat(100),List.of(),p);
        assertThat(result.size()).isGreaterThan(1);assertThat(result).allMatch(s->chunks.tokens(s.text())<=128);
    }
    @Test void markdownUsesAstHeadingsAndDoesNotPromoteHeadingsInsideFences(){
        String text="# Overview\n\nIntroduction.\n\n```text\n# not a heading\n```\n\n## Details\n\nActual details.";
        var result=chunks.split(text,List.of(),new KnowledgeProcessingPolicy(KnowledgeProcessingPolicy.Strategy.MARKDOWN_SECTION,128,16));
        assertThat(result).anyMatch(s->s.metadata().get("headingPath").equals(List.of("Overview","Details")));
        assertThat(result).noneMatch(s->s.metadata().get("headingPath").toString().contains("not a heading"));
    }
    @Test void policyChangesHaveDifferentFingerprintsAndPreviewDoesNotRequireAModel(){
        assertThat(KnowledgeProcessingPolicy.defaults().fingerprint()).isNotEqualTo(KnowledgeProcessingPolicy.legacy().fingerprint());
        assertThatThrownBy(()->new KnowledgeProcessingPolicy(KnowledgeProcessingPolicy.Strategy.FIXED_CHARACTER,64,16)).isInstanceOf(IllegalArgumentException.class);
        assertThat(chunks.split("simple text",List.of(),KnowledgeProcessingPolicy.defaults())).hasSize(1);
    }
}
