package com.spaceagent.platform.runtime.application;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.List;
/** Request-local output formatting, deliberately separate from durable Chat execution/recovery. */
final class ChatResponseFormatting {
    static String toolEvidence(String userMessage,List<String> toolResults){
        String evidence=clip(String.join("\n",toolResults),120_000);
        return "The authorized Tools have completed. Answer the user's original request in "
        + "clear natural language using the following Tool evidence. Do not return raw "
        + "JSON unless the user explicitly asks for JSON. Distinguish dates and sources "
        + "when present.\nOriginal request:\n"+clip(userMessage,4_000)+"\nTool evidence:\n"+evidence;
    }
    static String finalAnswer(String value){
        String content=value==null?"":value.trim();
        if(content.isBlank())throw new BusinessException("Model returned no final answer after Tool execution",HttpStatus.BAD_GATEWAY,"CHAT_FINAL_RESPONSE_EMPTY");
        return content;
    }
    static String joinReasoning(String first,String second){
        String left=first==null?"":first.trim(),right=second==null?"":second.trim();
        if(left.isBlank())return right;
        if(right.isBlank())return left;
        return left+"\n\n"+right;
    }
    private static String clip(String value,int maximum){
        return value==null?"":value.substring(0,Math.min(value.length(),maximum));
    }
}
