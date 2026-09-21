package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TransitionChatTaskCommand;
import com.spaceagent.platform.project.api.TaskTransition;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Set;

@Service
public class ChatRunLifecycleService implements ChatRunLifecycleApi {
    private final RuntimeApplicationApi runtime;
    private final ConversationApplicationApi conversations;
    private final TaskApplicationApi tasks;
    private final ModelCallLedgerApplicationApi models;
    private final ToolExecutionLedgerApplicationApi tools;
    private final ObjectMapper json;
    public ChatRunLifecycleService(RuntimeApplicationApi runtime,ConversationApplicationApi conversations,
            TaskApplicationApi tasks,ModelCallLedgerApplicationApi models,ToolExecutionLedgerApplicationApi tools,ObjectMapper json){
        this.runtime=runtime;this.conversations=conversations;this.tasks=tasks;this.models=models;this.tools=tools;this.json=json;
    }
    public Status latest(String tenant,String user,String conversation){
        conversations.find(conversation).filter(c->tenant.equals(c.tenantId())&&user.equals(c.userId())).orElseThrow(ChatRunLifecycleService::missing);
        return runtime.findLatestChatRun(tenant,user,conversation).map(this::view).orElse(null);
    }
    public Status get(String tenant,String user,String run){return view(owned(tenant,user,run));}
    public Status cancel(String tenant,String user,String runId){
        var run=owned(tenant,user,runId);
        if(!terminal(run.state())){
            try{runtime.cancel(new CancelAgentRunCommand(run.id(),"Cancelled by conversation owner"));}
            catch(BusinessException race){if(!terminal(owned(tenant,user,runId).state()))throw race;}
        }
        run=owned(tenant,user,runId);
        if(run.state()==AgentRunState.CANCELLED&&run.chatTaskId()!=null){
            try{tasks.transitionChatTask(new TransitionChatTaskCommand(tenant,user,run.conversationId(),run.chatTaskId(),TaskTransition.CANCEL));}
            catch(BusinessException alreadyTerminal){ /* Run cancellation remains authoritative. */ }
        }
        return view(run);
    }
    private AgentRunView owned(String tenant,String user,String id){
        return runtime.findRun(id).filter(run->tenant.equals(run.tenantId())&&user.equals(run.ownerId())&&run.projectId()==null)
                .orElseThrow(ChatRunLifecycleService::missing);
    }
    private Status view(AgentRunView run){
        String state=run.state().name(),approval=null,call=null,tool=null,plan=null;
        Long revision=null;
        JsonNode latest=runtime.findLatestCheckpoint(run.id()).map(this::parse).orElseGet(json::createObjectNode);
        if(run.state()==AgentRunState.WAITING_FOR_USER){
            JsonNode wait=latest.has("toolWait")?latest.path("toolWait"):latest.path("approval");
            if(wait.isObject()){
                state="UNKNOWN".equals(wait.path("waitKind").asText())?"WAITING_RECONCILIATION":"WAITING_APPROVAL";
                approval=text(wait,"pendingApprovalId");
                JsonNode pending=wait.path("toolCalls").path(wait.path("nextToolIndex").asInt());
                call=text(pending,"id");tool=text(pending,"name");
                if(wait.path("pendingToolRevision").isNumber())revision=wait.path("pendingToolRevision").asLong();
            }else if(latest.path("planReview").isObject()){
                state="WAITING_PLAN_APPROVAL";plan=text(latest.path("planReview"),"taskPlanId");
            }
        }
        int uncertain=0,active=0;
        Instant now=Instant.now();
        for(var model:models.findByRunId(run.id())){
            if(model.status().name().equals("UNKNOWN"))uncertain++;
            if(model.status().name().equals("RUNNING")){
                if(model.leaseUntil()==null||model.leaseUntil().isAfter(now))active++;else uncertain++;
            }
        }
        for(var effect:tools.findByRunId(run.id())){
            if(effect.status().name().equals("UNKNOWN"))uncertain++;
            if(Set.of("PENDING","RUNNING").contains(effect.status().name())){
                if(effect.leaseUntil()==null||effect.leaseUntil().isAfter(now))active++;else uncertain++;
            }
        }
        if(run.state()==AgentRunState.CANCELLED&&active>0)state="CANCELLING";
        String reservation=runtime.findLatestCheckpointByPhase(run.id(),"message-persisted")
                .map(this::parse).map(node->text(node,"assistantReservationId")).orElse(null);
        var reply=reservation==null?java.util.Optional.<com.spaceagent.platform.conversation.api.MessageView>empty():
                conversations.findReply(run.tenantId(),run.ownerId(),run.conversationId(),reservation);
        boolean partial=reply.map(message->message.role().equals("ASSISTANT_PARTIAL")).orElse(false);
        return new Status(run.id(),run.conversationId(),state,run.revision(),approval,call,tool,revision,
                run.chatTaskId(),plan,partial,uncertain,reply.map(message->message.createdAt().toString()).orElse(null));
    }
    private JsonNode parse(CheckpointView checkpoint){
        try{return json.readTree(checkpoint.stateSnapshot());}catch(Exception e){return json.createObjectNode();}
    }
    private static String text(JsonNode node,String key){return node.path(key).isTextual()?node.path(key).asText():null;}
    private static boolean terminal(AgentRunState state){return Set.of(AgentRunState.COMPLETED,AgentRunState.FAILED,AgentRunState.CANCELLED).contains(state);}
    private static BusinessException missing(){return new BusinessException("Chat Run not found",HttpStatus.NOT_FOUND,"CHAT_RUN_NOT_FOUND");}
}
