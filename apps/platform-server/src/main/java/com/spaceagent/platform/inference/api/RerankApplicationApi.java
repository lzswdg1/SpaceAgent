package com.spaceagent.platform.inference.api;
import java.util.*;

/** Trusted internal readonly compute API. Knowledge must authorize every text before dispatch. */
public interface RerankApplicationApi {
    Result rank(Request request);
    record Request(String tenantId,String actorId,String query,List<String> texts){
        public Request{texts=texts==null?List.of():List.copyOf(texts);}
        @Override public String toString(){return "RerankRequest[redacted]";}
    }
    record Score(int index,double score){}
    record Result(boolean succeeded,List<Score> scores,String modelId,String modelRevision,String safeCode){
        public Result{scores=List.copyOf(scores);}
    }
}
