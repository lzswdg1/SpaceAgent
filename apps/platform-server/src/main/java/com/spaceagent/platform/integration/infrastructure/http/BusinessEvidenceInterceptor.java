package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.governance.api.BusinessEvidenceApi;
import com.spaceagent.platform.integration.infrastructure.SystemAdminAuthenticationDetails;
import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

/** Records route templates, never raw URIs, query strings, bodies, headers or exception text. */
@Configuration @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class BusinessEvidenceInterceptor implements AsyncHandlerInterceptor,WebMvcConfigurer {
    private static final String ATTRIBUTE=BusinessEvidenceInterceptor.class.getName()+".attempt";
    private static final Set<String> RESOURCES=Set.of("userId","organizationId","agentId","projectId","conversationId","taskId","runId","baseId","documentId","connectionId","requestId","commandId");
    private final BusinessEvidenceApi evidence;
    public BusinessEvidenceInterceptor(BusinessEvidenceApi evidence){this.evidence=evidence;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/v1/**","/internal/system-admin/v1/**");}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        if(request.getAttribute(ATTRIBUTE)!=null || !(handler instanceof HandlerMethod) || !Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod()))return true;
        var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null || !auth.isAuthenticated())return true;
        String actor,tenant,kind;
        if(auth.getDetails() instanceof TenantAuthenticationDetails details){actor=auth.getName();tenant=details.tenantId();kind="USER";}
        else if(auth.getDetails() instanceof SystemAdminAuthenticationDetails details){actor=details.actorId();tenant=null;kind="SYSTEM_ADMIN";}
        else return true; // Anonymous login/registration/security denials have separate Identity evidence.
        String route=(String)request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if(route==null)return true;
        String resource=null,resourceKind="REQUEST";
        Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(variables instanceof Map<?,?> values)for(String key:RESOURCES.stream().sorted().toList()){
            if(values.get(key) instanceof String value && canonicalId(value)){resource=value;resourceKind=key.replaceAll("([a-z])([A-Z])","$1_$2").toUpperCase(Locale.ROOT);break;}}
        if(auth.getDetails() instanceof SystemAdminAuthenticationDetails d && canonicalId(d.commandId())){resource=d.commandId();resourceKind="ADMIN_COMMAND";}
        String id=evidence.admit(new BusinessEvidenceApi.Attempt(actor,tenant,kind,request.getMethod(),route,resourceKind,resource));
        request.setAttribute(ATTRIBUTE,id);response.setHeader("X-Business-Audit-ID",id);return true;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception exception){
        if(request.getAttribute(ATTRIBUTE) instanceof String id)try{evidence.finish(id,response.getStatus(),exception!=null);}
        catch(RuntimeException ignored){/* The independently committed attempt remains visibly UNCONFIRMED. Never retry the business mutation. */}
    }
    private static boolean canonicalId(String value){try{return value!=null && UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}}
}
