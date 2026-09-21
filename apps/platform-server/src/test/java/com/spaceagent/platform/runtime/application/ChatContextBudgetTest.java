package com.spaceagent.platform.runtime.application;
import com.spaceagent.platform.inference.api.InferenceExecutionApi.InferenceMessage;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class ChatContextBudgetTest {
    @Test void repeatedEvidenceAndContinuationStayInsideTheTotalWindowWithNewestEvidenceRetained(){
        String question="分析代码",schema="{tool definitions}";
        var messages=List.of(new InferenceMessage("system","Stay read-only"),new InferenceMessage("user","旧历史".repeat(8000)),
                new InferenceMessage("user",question),new InferenceMessage("user","NEW EVIDENCE START"+"代码内容".repeat(8000)+"NEW EVIDENCE END"));
        var result=ChatContextBudget.fit(messages,question,8192,1024,schema);
        assertThat(result.stream().mapToInt(ChatContextBudget::cost).sum()+ChatContextBudget.bytes(schema)+1024+1024).isLessThanOrEqualTo(8192);
        assertThat(result.toString()).contains("NEW EVIDENCE START","NEW EVIDENCE END",question,"Stay read-only","CONTEXT EXCERPT");
    }
    @Test void mandatoryInstructionsAndOutputCannotSilentlyOverflow(){
        assertThatThrownBy(()->ChatContextBudget.fit(List.of(new InferenceMessage("system","x".repeat(3000))),"q",4096,1024,"{}"))
                .isInstanceOf(BusinessException.class);
    }
}
