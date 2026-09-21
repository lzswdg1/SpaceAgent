package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.SetConversationActiveTaskCommand;
import com.spaceagent.platform.integration.api.GithubMcpProjectImportApplicationApi;
import com.spaceagent.platform.integration.api.ProjectStartApplicationApi;
import com.spaceagent.platform.integration.domain.ProjectStartRequestRepository;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.RepositoryBranchVisibility;
import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Server-owned workflow receipts; coding setup is one DB transaction, remote root setup is resumable. */
@Service
public class ProjectStartApplicationService implements ProjectStartApplicationApi {
    private final ProjectStartRequestRepository receipts;
    private final ProjectApplicationApi projects;
    private final ProjectDirectoryApplicationApi directories;
    private final ProjectRootApplicationApi roots;
    private final SourceRepositoryApplicationApi sources;
    private final GithubMcpProjectImportApplicationApi github;
    private final ConversationApplicationApi conversations;
    private final AgentApplicationApi agents;
    private final TaskApplicationApi tasks;
    private final TaskPlanApplicationApi plans;
    private final ProjectCodingCoordinator coding;
    private final ProjectPlanExecutionApplicationApi executions;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public ProjectStartApplicationService(ProjectStartRequestRepository receipts,ProjectApplicationApi projects,
            ProjectDirectoryApplicationApi directories,ProjectRootApplicationApi roots,SourceRepositoryApplicationApi sources,
            GithubMcpProjectImportApplicationApi github,ConversationApplicationApi conversations,AgentApplicationApi agents,
            TaskApplicationApi tasks,TaskPlanApplicationApi plans,ProjectCodingCoordinator coding,
            ProjectPlanExecutionApplicationApi executions,ObjectMapper json,PlatformTransactionManager transactionManager){
        this.receipts=receipts;this.projects=projects;this.directories=directories;this.roots=roots;this.sources=sources;
        this.github=github;this.conversations=conversations;this.agents=agents;this.tasks=tasks;this.plans=plans;
        this.coding=coding;this.executions=executions;this.json=json;this.transactions=new TransactionTemplate(transactionManager);
    }
    @Override public CodingResult coding(String tenant,String user,String key,CodingInput input){
        return transactions.execute(tx->{
            projects.getProject(new GetProjectQuery(tenant,user,input.projectId()));
            var directory=directories.get(new ProjectDirectoryApplicationApi.Query(tenant,user,input.projectId(),input.directoryId()));
            if(!directory.state().name().equals("ACTIVE")||!Objects.equals(directory.sourceRepositoryId(),input.sourceId()))throw invalid("PROJECT_START_DIRECTORY_MISMATCH");
            var source=sources.get(new GetSourceRepositoryQuery(tenant,user,input.projectId(),input.sourceId()));
            var conversation=conversations.find(input.conversationId()).filter(c->tenant.equals(c.tenantId())&&user.equals(c.userId())
                    &&input.projectId().equals(c.projectId())&&input.directoryId().equals(c.projectDirectoryId())&&c.status().name().equals("ACTIVE"))
                    .orElseThrow(()->missing("PROJECT_START_CONVERSATION_NOT_FOUND"));
            String goal=bounded(input.goal(),"goal",4000);
            String base=input.baseRef()==null||input.baseRef().isBlank()?source.defaultBranch():input.baseRef().trim();
            if(!RepositoryBranchVisibility.visible(base))throw invalid("PROJECT_START_BRANCH_INVALID");
            var normalized=new CodingInput(input.projectId(),input.directoryId(),input.sourceId(),input.conversationId(),input.agentId(),input.reviewerAgentId(),base,goal);
            Claim claim=claim(tenant,user,"CODING",key,normalized,input.projectId());
            if(!claim.created()){
                if(claim.request().result()==null)throw conflict("PROJECT_START_IN_PROGRESS");
                return decode(claim.request().result(),CodingResult.class);
            }
            if(conversation.activeTaskId()!=null){
                var activeTask=tasks.getTask(new GetTaskQuery(tenant,user,input.projectId(),conversation.activeTaskId()));
                if(activeTask.currentTaskPlanId()!=null){
                    var activeExecution=executions.getActiveByTaskPlan(new ProjectPlanExecutionApplicationApi.QueryByTaskPlan(tenant,user,activeTask.currentTaskPlanId()));
                    if(activeExecution.isPresent())throw conflict("PROJECT_CODING_ALREADY_RUNNING");
                }
            }
            if(input.agentId().equals(input.reviewerAgentId()))throw invalid("PROJECT_CODING_REVIEWER_NOT_DISTINCT");
            AgentDefinitionView agent=ownedAgent(tenant,user,input.agentId());ownedAgent(tenant,user,input.reviewerAgentId());
            if(agent.modelPoolId()==null&&(agent.modelProviderId()==null||agent.modelId()==null))throw invalid("MODEL_BINDING_INCOMPLETE");
            var enabled=new LinkedHashSet<>(agent.enabledToolIds());
            if(enabled.addAll(List.of("file_list","file_read","coding_write_file","coding_delete_file","coding_run_command"))){
                agents.update(new UpdateAgentDefinitionCommand(user,tenant,agent.id(),null,null,null,null,null,null,
                        null,null,null,null,null,null,null,null,List.copyOf(enabled),null));
            }
            var root=tasks.createTask(new CreateTaskCommand(tenant,user,input.projectId(),null,goal.substring(0,Math.min(200,goal.length())),goal,null,List.of(),List.of()));
            conversations.setActiveTask(new SetConversationActiveTaskCommand(tenant,user,input.conversationId(),root.id()));
            var child=tasks.createTask(new CreateTaskCommand(tenant,user,input.projectId(),root.id(),root.title(),goal,null,List.of(),List.of()));
            var plan=plans.createPlan(new CreateTaskPlanCommand(tenant,user,input.projectId(),root.id(),null,List.of(
                    new CreateTaskPlanCommand.PlanStepDraft("implement",child.id(),List.of(),null,agent.id(),goal,
                            List.of("Implement the requested change and verify it with relevant tests"),false))));
            plans.transition(new TaskPlanActionCommand(tenant,user,input.projectId(),root.id(),plan.id(),TaskPlanAction.PROPOSE));
            plans.transition(new TaskPlanActionCommand(tenant,user,input.projectId(),root.id(),plan.id(),TaskPlanAction.APPROVE));
            var dispatched=coding.dispatch(new com.spaceagent.platform.integration.api.ProjectPlanExecutionApplicationApi.DispatchCommand(
                    tenant,user,input.projectId(),root.id(),plan.id(),input.directoryId(),input.conversationId(),input.sourceId(),
                    input.agentId(),null,null,base,input.reviewerAgentId()));
            var result=new CodingResult(tasks.getTask(new GetTaskQuery(tenant,user,input.projectId(),root.id())),child,
                    plans.getPlan(new GetTaskPlanQuery(tenant,user,input.projectId(),root.id(),plan.id())),
                    executions.get(new ProjectPlanExecutionApplicationApi.Query(tenant,user,input.projectId(),dispatched.executionId())));
            receipts.complete(claim.request().id(),encode(result),Instant.now());return result;
        });
    }
    @Override public ProjectRootApplicationApi.RootView githubRoot(String tenant,String user,String key,RootInput input){
        String name=bounded(input.name(),"name",120),url=bounded(input.githubUrl(),"githubUrl",2048);
        try{
            var uri=java.net.URI.create(url);
            if(!"https".equalsIgnoreCase(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())
                    ||uri.getPort()!=-1&&uri.getPort()!=443||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                    ||!uri.getPath().matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?"))throw invalid("PROJECT_ROOT_GITHUB_URL_INVALID");
            UUID.fromString(input.connectionId());
        }catch(IllegalArgumentException error){throw invalid("PROJECT_ROOT_GITHUB_URL_INVALID");}
        var normalized=new RootInput(name,input.connectionId(),url);
        var receipt=transactions.execute(tx->{
            Claim claim=claim(tenant,user,"GITHUB_ROOT",key,normalized,null);
            if(!claim.created()){
                if(claim.request().projectId()!=null)projects.getProject(new GetProjectQuery(tenant,user,claim.request().projectId()));
                return claim.request();
            }
            var project=projects.createProject(new CreateProjectCommand(tenant,user,name,null));
            // This unused default is created and archived in the same transaction, before anyone can use it.
            directories.list(new ProjectDirectoryApplicationApi.ListQuery(tenant,user,project.id())).stream().filter(d->d.defaultDirectory())
                    .forEach(d->directories.archive(new ProjectDirectoryApplicationApi.ArchiveCommand(tenant,user,project.id(),d.id())));
            receipts.bindProject(claim.request().id(),project.id(),Instant.now());
            return new ProjectStartRequestRepository.Request(claim.request().id(),tenant,user,"GITHUB_ROOT",claim.request().keyHash(),claim.request().requestHash(),"PENDING",project.id(),null,claim.request().createdAt(),Instant.now());
        });
        if(receipt.result()!=null)return decode(receipt.result(),ProjectRootApplicationApi.RootView.class);
        var source=github.importRepository(new GithubMcpProjectImportApplicationApi.Command(tenant,user,receipt.projectId(),input.connectionId(),null,url,"root-start:"+receipt.id()));
        var root=roots.create(new ProjectRootApplicationApi.CreateCommand(tenant,user,receipt.projectId(),source.id(),name));
        root=roots.rename(tenant,user,root.id(),name);
        var completed=root;
        transactions.executeWithoutResult(tx->receipts.complete(receipt.id(),encode(completed),Instant.now()));
        return root;
    }
    private Claim claim(String tenant,String user,String kind,String key,Object input,String project){
        if(key==null||key.trim().length()<8)throw invalid("PROJECT_START_IDEMPOTENCY_KEY_REQUIRED");
        String keyHash=hash(bounded(key,"Idempotency-Key",200)),inputHash=hash(encode(input));
        var existing=receipts.find(tenant,user,kind,keyHash).orElse(null);
        if(existing!=null){if(!existing.requestHash().equals(inputHash))throw conflict("PROJECT_START_IDEMPOTENCY_CONFLICT");return new Claim(existing,false);}
        if(kind.equals("GITHUB_ROOT")){
            var pending=receipts.pendingRoot(tenant,user,inputHash).orElse(null);
            if(pending!=null){
                if(!receipts.alias(pending,keyHash)){
                    var winner=receipts.find(tenant,user,kind,keyHash).orElseThrow(()->conflict("PROJECT_START_IDEMPOTENCY_CONFLICT"));
                    if(!winner.requestHash().equals(inputHash))throw conflict("PROJECT_START_IDEMPOTENCY_CONFLICT");
                    return new Claim(winner,false);
                }
                return new Claim(pending,false);
            }
        }
        Instant now=Instant.now();var record=new ProjectStartRequestRepository.Request(UUID.randomUUID().toString(),tenant,user,kind,keyHash,inputHash,"PENDING",project,null,now,now);
        if(!receipts.insert(record)){
            existing=receipts.find(tenant,user,kind,keyHash).orElseGet(()->kind.equals("GITHUB_ROOT")?receipts.pendingRoot(tenant,user,inputHash).orElse(null):null);
            if(existing==null||!existing.requestHash().equals(inputHash))throw conflict("PROJECT_START_IDEMPOTENCY_CONFLICT");
            if(receipts.find(tenant,user,kind,keyHash).isEmpty()&&!receipts.alias(existing,keyHash))throw conflict("PROJECT_START_IDEMPOTENCY_CONFLICT");
            return new Claim(existing,false);
        }
        return new Claim(record,true);
    }
    private AgentDefinitionView ownedAgent(String tenant,String user,String id){return agents.findById(id)
            .filter(a->tenant.equals(a.tenantId())&&user.equals(a.ownerId())&&a.status().name().equals("ACTIVE"))
            .orElseThrow(()->missing("PROJECT_START_AGENT_NOT_FOUND"));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException("Unable to encode workflow receipt",error);}}
    private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception error){throw new IllegalStateException("Invalid workflow receipt",error);}}
    private static String bounded(String value,String field,int max){if(value==null||value.isBlank()||value.trim().length()>max)throw invalid("PROJECT_START_INPUT_INVALID");return value.trim();}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException invalid(String code){return new BusinessException(code,HttpStatus.BAD_REQUEST,code);}
    private static BusinessException conflict(String code){return new BusinessException(code,HttpStatus.CONFLICT,code);}
    private static BusinessException missing(String code){return new BusinessException(code,HttpStatus.NOT_FOUND,code);}
    private record Claim(ProjectStartRequestRepository.Request request,boolean created){}
}
