package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.inference.api.InferenceExecutionApi.InferenceMessage;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Provider-neutral conservative byte upper bound, including schema/message overhead and output reserve. */
final class ChatContextBudget {
    private ChatContextBudget() { }
    static List<InferenceMessage> fit(List<InferenceMessage> messages,String question,int window,int output,String toolSchema) {
        long available=(long)window-output-bytes(toolSchema)-1024;
        if(available<128)throw exceeded();
        long total=messages.stream().mapToLong(ChatContextBudget::cost).sum();
        if(total<=available)return messages;
        var notice=new InferenceMessage("system","Some earlier context or long evidence was excerpted to fit the model window. Excerpts are not complete files; state limitations.");
        available-=cost(notice);
        var selected=new TreeMap<Integer,InferenceMessage>();
        for(int i=0;i<messages.size();i++){
            var message=messages.get(i);
            boolean mandatory=message.role().equals("system") || message.role().equals("user")&&message.content().equals(question)
                    || i==messages.size()-1&&message.role().equals("user")&&bytes(message.content())<=4096;
            if(mandatory){available-=cost(message);selected.put(i,message);}
        }
        if(available<0)throw exceeded();
        // New Tool evidence and the tail of an interrupted answer take precedence over old history.
        for(int i=messages.size()-1;i>=0&&available>128;i--){
            if(selected.containsKey(i))continue;
            var message=messages.get(i);
            String value=cost(message)<=available?message.content():excerpt(message.content(),(int)Math.min(Integer.MAX_VALUE,available-64));
            var bounded=new InferenceMessage(message.role(),value);selected.put(i,bounded);available-=cost(bounded);
        }
        var result=new ArrayList<>(selected.values());result.addFirst(notice);return result;
    }
    static int bytes(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    static int cost(InferenceMessage message){return bytes(message.content())+64;}
    private static String excerpt(String text,int maximum){
        String marker="\n[CONTEXT EXCERPT: middle omitted]\n";
        int allowance=Math.max(0,maximum-bytes(marker));
        return prefix(text,allowance/2)+marker+suffix(text,allowance-allowance/2);
    }
    private static String prefix(String text,int maximum){
        int end=0,used=0;
        while(end<text.length()){
            int point=text.codePointAt(end),cost=bytes(new String(Character.toChars(point)));
            if(used+cost>maximum)break;used+=cost;end+=Character.charCount(point);
        }
        return text.substring(0,end);
    }
    private static String suffix(String text,int maximum){
        int start=text.length(),used=0;
        while(start>0){
            int point=text.codePointBefore(start),cost=bytes(new String(Character.toChars(point)));
            if(used+cost>maximum)break;used+=cost;start-=Character.charCount(point);
        }
        return text.substring(start);
    }
    private static BusinessException exceeded(){return new BusinessException("Instructions and output reserve exceed the model context budget",HttpStatus.PAYLOAD_TOO_LARGE,"CHAT_CONTEXT_BUDGET_EXCEEDED");}
}
