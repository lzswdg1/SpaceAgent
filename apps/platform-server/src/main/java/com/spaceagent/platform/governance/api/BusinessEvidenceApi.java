package com.spaceagent.platform.governance.api;
import java.time.Instant;
import java.util.List;

public interface BusinessEvidenceApi {
    String admit(Attempt attempt);
    void finish(String attemptId,int httpStatus);
    default void finish(String attemptId,int httpStatus,boolean transportFailed){finish(attemptId,httpStatus);}
    Page timeline(Filter filter);
    record Attempt(String actorId,String tenantId,String actorKind,String method,String route,String resourceKind,String resourceId){}
    record Filter(String actorId,String tenantId,String outcome,Instant from,Instant to,int page,int pageSize){}
    record Entry(String id,String actorId,String tenantId,String actorKind,String method,String route,String resourceKind,String resourceId,
                 String outcome,Integer httpStatus,Instant admittedAt,Instant observedAt){}
    record Page(List<Entry> items,long total,String coverage){public Page{items=List.copyOf(items);}}
}
