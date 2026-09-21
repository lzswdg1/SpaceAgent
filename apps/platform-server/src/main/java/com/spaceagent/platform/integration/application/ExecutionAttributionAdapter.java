package com.spaceagent.platform.integration.application;
import com.spaceagent.platform.shared.api.ExecutionAttributionQuery;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import org.springframework.stereotype.Component;
import java.util.Optional;
@Component public class ExecutionAttributionAdapter implements ExecutionAttributionQuery {
    private final org.springframework.beans.factory.ObjectProvider<RuntimeApplicationApi> runtime;
    public ExecutionAttributionAdapter(org.springframework.beans.factory.ObjectProvider<RuntimeApplicationApi> runtime){this.runtime=runtime;}
    public Optional<Scope> resolve(String run){return runtime.getObject().findRun(run).map(r->new Scope(r.tenantId(),r.ownerId()));}
}
