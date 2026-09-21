package com.spaceagent.admin.dashboard;
import com.spaceagent.admin.platformclient.BusinessEvidenceWire.*;
import com.spaceagent.admin.shared.AdminApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/admin/v1")
public class AdminBusinessEvidenceController {
    private final AdminPlatformReadService reads;public AdminBusinessEvidenceController(AdminPlatformReadService reads){this.reads=reads;}
    @GetMapping("/business-audit") public AdminApiResponse<AuditPage> audit(@RequestParam Map<String,String> params,@AuthenticationPrincipal Jwt jwt){
        return AdminApiResponse.ok(reads.businessAudit(filter(params,"userId","organizationId","outcome","from","to","page","pageSize"),actor(jwt),session(jwt),request()));}
    @GetMapping("/resource-capabilities") public AdminApiResponse<List<Capability>> capabilities(@AuthenticationPrincipal Jwt jwt){return AdminApiResponse.ok(reads.resourceCapabilities(actor(jwt),session(jwt),request()));}
    @GetMapping("/agent-change-evidence") public AdminApiResponse<ReviewPage> reviews(@RequestParam Map<String,String> params,@AuthenticationPrincipal Jwt jwt){
        return AdminApiResponse.ok(reads.agentChangeEvidence(filter(params,"userId","organizationId","page","pageSize"),actor(jwt),session(jwt),request()));}
    @GetMapping("/usage/summary") public AdminApiResponse<UsageOverview> summary(@RequestParam Map<String,String> params,@AuthenticationPrincipal Jwt jwt){
        return AdminApiResponse.ok(reads.usageSummary(filter(params,"userId","organizationId","from","to"),actor(jwt),session(jwt),request()));}
    @GetMapping("/usage/history") public AdminApiResponse<UsageHistory> history(@RequestParam Map<String,String> params,@AuthenticationPrincipal Jwt jwt){
        return AdminApiResponse.ok(reads.usageHistory(filter(params,"kind","userId","organizationId","status","from","to","page","pageSize"),actor(jwt),session(jwt),request()));}
    private static Map<String,String> filter(Map<String,String> input,String... allowed){
        try{var result=new TreeMap<String,String>();for(String key:allowed){String value=input.get(key);if(value!=null){if(value.length()>120)throw new IllegalArgumentException();result.put(key,value);}}
            for(String id:List.of("userId","organizationId"))if(result.containsKey(id)&&!result.get(id).matches("[A-Za-z0-9_.:-]{1,36}"))throw new IllegalArgumentException();
            int page=Integer.parseInt(result.getOrDefault("page","0")),size=Integer.parseInt(result.getOrDefault("pageSize","25"));if(page<0||page>10000||size<1||size>100)throw new IllegalArgumentException();
            if(Arrays.asList(allowed).contains("kind")&&!Set.of("MODEL","EMBEDDING","TOOL").contains(result.getOrDefault("kind","")))throw new IllegalArgumentException();
            if(result.containsKey("status")&&!result.get("status").matches("[A-Z_]{1,32}"))throw new IllegalArgumentException();
            if(result.containsKey("outcome")&&!Set.of("HTTP_ACCEPTED","HTTP_SUCCEEDED","HTTP_REJECTED","HTTP_FAILED","UNCONFIRMED").contains(result.get("outcome")))throw new IllegalArgumentException();
            var end=result.containsKey("to")?java.time.Instant.parse(result.get("to")):java.time.Instant.now();var start=result.containsKey("from")?java.time.Instant.parse(result.get("from")):end.minus(java.time.Duration.ofDays(1));
            if(!start.isBefore(end)||java.time.Duration.between(start,end).compareTo(java.time.Duration.ofDays(90))>0)throw new IllegalArgumentException();return result;
        }catch(RuntimeException e){throw new com.spaceagent.admin.shared.AdminApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"ADMIN_QUERY_INVALID","Invalid bounded administrator query");}
    }
    private static UUID actor(Jwt jwt){return UUID.fromString(jwt.getSubject());}
    @GetMapping("/resource-observations") public AdminApiResponse<ResourceObservations> resources(@RequestParam Map<String,String> params,@AuthenticationPrincipal Jwt jwt){
        return AdminApiResponse.ok(reads.resourceObservations(filter(params,"userId","organizationId","from","to"),actor(jwt),session(jwt),request()));}
    private static UUID session(Jwt jwt){return UUID.fromString(jwt.getClaimAsString("session_id"));}
    private static String request(){return UUID.randomUUID().toString();}
}
