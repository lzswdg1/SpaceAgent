package com.spaceagent.platform.governance.domain;
import java.time.Instant;
import java.util.List;
public interface BusinessEvidenceRepository {
    void admit(String id,String actor,String tenant,String actorKind,String method,String route,String resourceKind,String resourceId);
    void finish(String id,int status,String outcome);
    List<Row> list(String actor,String tenant,String outcome,Instant from,Instant to,int offset,int limit);
    long count(String actor,String tenant,String outcome,Instant from,Instant to);
    record Row(String id,String actor,String tenant,String actorKind,String method,String route,String resourceKind,String resourceId,
               String outcome,Integer status,Instant admittedAt,Instant observedAt){}
}
