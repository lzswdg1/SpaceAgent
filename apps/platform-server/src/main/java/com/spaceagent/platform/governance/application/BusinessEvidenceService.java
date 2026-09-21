package com.spaceagent.platform.governance.application;
import com.spaceagent.platform.governance.api.BusinessEvidenceApi;
import com.spaceagent.platform.governance.domain.BusinessEvidenceRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Service @ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class BusinessEvidenceService implements BusinessEvidenceApi {
    private final BusinessEvidenceRepository repository;
    public BusinessEvidenceService(BusinessEvidenceRepository repository){this.repository=repository;}
    public String admit(Attempt a){
        if(a==null || !id(a.actorId()) || a.tenantId()!=null&&!id(a.tenantId()) || a.resourceId()!=null&&!id(a.resourceId())
            || !Set.of("USER","SYSTEM_ADMIN").contains(a.actorKind()) || !Set.of("POST","PUT","PATCH","DELETE").contains(a.method())
            || a.route()==null || a.route().length()>240 || !a.route().matches("/[A-Za-z0-9_/{\\}*:.?-]+")
            || a.resourceKind()==null || !a.resourceKind().matches("[A-Z_]{1,40}"))throw invalid();
        String id=UUID.randomUUID().toString();repository.admit(id,a.actorId(),a.tenantId(),a.actorKind(),a.method(),a.route(),a.resourceKind(),a.resourceId());return id;
    }
    public void finish(String id,int status){finish(id,status,false);}
    public void finish(String id,int status,boolean failed){if(!id(id)||status<100||status>599)throw invalid();
        repository.finish(id,status,failed?"HTTP_FAILED":status==202?"HTTP_ACCEPTED":status<400?"HTTP_SUCCEEDED":status<500?"HTTP_REJECTED":"HTTP_FAILED");}
    public Page timeline(Filter f){
        if(f==null || f.from()==null || f.to()==null || !f.from().isBefore(f.to()) || Duration.between(f.from(),f.to()).compareTo(Duration.ofDays(90))>0
            || f.page()<0 || f.page()>10000 || f.pageSize()<1 || f.pageSize()>100 || f.actorId()!=null&&!id(f.actorId()) || f.tenantId()!=null&&!id(f.tenantId())
            || f.outcome()!=null&&!Set.of("HTTP_ACCEPTED","HTTP_SUCCEEDED","HTTP_REJECTED","HTTP_FAILED","UNCONFIRMED").contains(f.outcome()))throw invalid();
        var rows=repository.list(f.actorId(),f.tenantId(),f.outcome(),f.from(),f.to(),f.page()*f.pageSize(),f.pageSize());
        return new Page(rows.stream().map(r->new Entry(r.id(),r.actor(),r.tenant(),r.actorKind(),r.method(),r.route(),r.resourceKind(),r.resourceId(),r.outcome(),r.status(),r.admittedAt(),r.observedAt())).toList(),
            repository.count(f.actorId(),f.tenantId(),f.outcome(),f.from(),f.to()),"AUTHENTICATED_MATCHED_MUTATIONS_SINCE_V1095; HTTP_OUTCOME_NOT_ASYNC_EFFECT; MISSING_OUTCOME_UNCONFIRMED");
    }
    private static boolean id(String s){return s!=null && s.matches("[A-Za-z0-9_.:-]{1,36}");}
    private static BusinessException invalid(){return new BusinessException("Invalid business evidence request",HttpStatus.BAD_REQUEST,"BUSINESS_EVIDENCE_INVALID");}
}
