package com.spaceagent.platform.project.infrastructure;
import com.spaceagent.platform.project.api.ProjectRootApplicationApi;
import com.spaceagent.platform.project.domain.ProjectDirectoryRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${platform.runtime.role:api}' != 'knowledge-worker'")
public class ManagedRootStorageInitializer {
    private final ProjectDirectoryRepository directories;private final ProjectRootApplicationApi roots;
    public ManagedRootStorageInitializer(ProjectDirectoryRepository directories,ProjectRootApplicationApi roots){this.directories=directories;this.roots=roots;}
    @EventListener(ApplicationReadyEvent.class)
    public void initialize(){for(var directory:directories.findPendingManagedRoots()){
        try{roots.prepare(directory.tenantId(),directory.createdBy(),directory.id());}
        catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Managed root initialization pending: rootId={}, exceptionType={}",directory.id(),error.getClass().getSimpleName());}
    }}
}
