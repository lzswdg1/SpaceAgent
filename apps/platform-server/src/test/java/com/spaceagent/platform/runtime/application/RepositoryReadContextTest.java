package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi.InferenceToolCall;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RepositoryReadContextTest {
    @Test void oversizedEarlierResultsCannotEraseNewFileEvidence() {
        var context = new RepositoryReadContext(new ObjectMapper());
        context.assistantTurn("Inspecting files");
        for (int index = 0; index < 4; index++) {
            context.record(new InferenceToolCall("id-"+index,"file_read","{\"path\":\"file"+index+"\"}"),
                    "SUCCEEDED", "START-"+index+"x".repeat(90_000)+"END-"+index);
        }
        var messages = context.messages(List.of(), false);
        String evidence=messages.getLast().content();
        assertThat(evidence.length()).isLessThan(129_000);
        for (int index=0;index<4;index++) assertThat(evidence).contains("START-"+index,"END-"+index,"file"+index);
        assertThat(evidence).contains("TRUNCATED");
        context.record(new InferenceToolCall("new","file_read","{\"path\":\"newest\"}"),"SUCCEEDED","NEWEST RESULT");
        assertThat(context.messages(List.of(),false).getLast().content()).contains("NEWEST RESULT").isNotEqualTo(evidence);
    }
    @Test void deduplicatesSemanticReadArgumentsButAllowsDifferentReadLimits() {
        var context=new RepositoryReadContext(new ObjectMapper());
        var call=new InferenceToolCall("first","file_read","{\"workspaceId\":\"w\",\"path\":\"README\"}");
        context.record(call,"SUCCEEDED","read once");
        assertThat(context.contains(new InferenceToolCall("different-id","file_read","{\"path\":\"README\",\"workspaceId\":\"w\"}"))).isTrue();
        assertThat(context.contains(new InferenceToolCall("larger","file_read","{\"path\":\"README\",\"workspaceId\":\"w\",\"maxBytes\":9000}"))).isFalse();
        assertThat(context.messages(List.of(),true).getFirst().content()).contains("answer now");
    }
    @Test void continuationRemovesOnlySubstantialOverlappingBoundaries() {
        String boundary="A sufficiently long paragraph at the response boundary.";
        assertThat(RepositoryAnswerContinuation.suffix("prefix "+boundary,boundary+" remainder")).isEqualTo(" remainder");
        assertThat(RepositoryAnswerContinuation.suffix("a","another section")).isEqualTo("another section");
        assertThat(RepositoryAnswerContinuation.limited("length")).isTrue();
        assertThat(RepositoryAnswerContinuation.limited("stop")).isFalse();
    }
}
