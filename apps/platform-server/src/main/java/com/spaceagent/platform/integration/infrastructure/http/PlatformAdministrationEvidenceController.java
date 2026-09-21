package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.governance.api.BusinessEvidenceApi;
import com.spaceagent.platform.agent.api.AgentReviewEvidenceApi;
import com.spaceagent.platform.inference.api.InferenceAdministrationUsageApi;
import com.spaceagent.platform.tooling.api.ToolingAdministrationUsageApi;
import com.spaceagent.platform.integration.application.AdministrationCapabilityCatalog;
import com.spaceagent.platform.shared.api.AdministrationUsage;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController @RequestMapping("/internal/system-admin/v1") @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
@org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class PlatformAdministrationEvidenceController {
    private final BusinessEvidenceApi audit;private final AgentReviewEvidenceApi reviews;private final AdministrationCapabilityCatalog catalog;
    private final InferenceAdministrationUsageApi inference;private final ToolingAdministrationUsageApi tooling;private final TimeProvider time;
    public PlatformAdministrationEvidenceController(BusinessEvidenceApi audit,AgentReviewEvidenceApi reviews,AdministrationCapabilityCatalog catalog,InferenceAdministrationUsageApi inference,ToolingAdministrationUsageApi tooling,TimeProvider time){this.audit=audit;this.reviews=reviews;this.catalog=catalog;this.inference=inference;this.tooling=tooling;this.time=time;}
    @GetMapping("/business-audit") public ApiResponse<BusinessEvidenceApi.Page> audit(@RequestParam(required=false) String userId,@RequestParam(required=false) String organizationId,
        @RequestParam(required=false) String outcome,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int pageSize){
        Instant end=to==null?time.now():to;return ApiResponse.ok(audit.timeline(new BusinessEvidenceApi.Filter(userId,organizationId,outcome,from==null?end.minus(Duration.ofDays(1)):from,end,page,pageSize)));}
    @GetMapping("/resource-capabilities") public ApiResponse<List<AdministrationCapabilityCatalog.Capability>> capabilities(){return ApiResponse.ok(catalog.capabilities());}
    @GetMapping("/agent-change-evidence") public ApiResponse<AgentReviewEvidenceApi.Page> reviews(@RequestParam(required=false) String userId,@RequestParam(required=false) String organizationId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int pageSize){
        try{return ApiResponse.ok(reviews.reviews(userId,organizationId,page,pageSize));}catch(IllegalArgumentException e){throw invalid();}}
    @GetMapping("/usage/summary") public ApiResponse<UsageOverview> summary(@RequestParam(required=false) String userId,@RequestParam(required=false) String organizationId,
        @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){
        var f=filter(userId,organizationId,from,to,null,0,1);return ApiResponse.ok(new UsageOverview(f.from(),f.to(),List.of(inference.summary("MODEL",f),inference.summary("EMBEDDING",f),tooling.summary(f)),"RETAINED_OWNER_LEDGER_ROWS; CREATED_AT_WINDOW; CURRENT_OBSERVED_STATES; LOGICAL_ATTEMPTS_NOT_BILLABLE_REQUESTS; NO_RESOURCE_MEASUREMENTS",userId,organizationId,time.now()));}
    @GetMapping("/usage/history") public ApiResponse<AdministrationUsage.History> history(@RequestParam String kind,@RequestParam(required=false) String userId,@RequestParam(required=false) String organizationId,
        @RequestParam(required=false) String status,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int pageSize){
        var f=filter(userId,organizationId,from,to,status,page,pageSize);try{return ApiResponse.ok(kind.equals("TOOL")?tooling.history(f):inference.history(kind,f));}catch(IllegalArgumentException e){throw invalid();}}
    public record UsageOverview(Instant from,Instant to,List<AdministrationUsage.Summary> summaries,String coverage,String userId,String organizationId,Instant generatedAt){}
    private AdministrationUsage.Filter filter(String user,String tenant,Instant from,Instant to,String status,int page,int size){
        Instant end=to==null?time.now():to;try{return new AdministrationUsage.Filter(user,tenant,from==null?end.minus(Duration.ofDays(1)):from,end,status,page,size);}catch(IllegalArgumentException e){throw invalid();}}
    private static BusinessException invalid(){return new BusinessException("Invalid bounded administrator query",HttpStatus.BAD_REQUEST,"SYSTEM_ADMIN_QUERY_INVALID");}
}
