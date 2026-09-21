package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.ToolClaimOwnerProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JVM-lifetime diagnostic owner identifier for tool claims.
 *
 * <p>The claim token and revision provide fencing. This owner value exists only to make
 * an active claim attributable during operations and remains stable for this JVM.
 */
@Component
public class JvmToolClaimOwnerProvider implements ToolClaimOwnerProvider {

    private final String ownerId = "jvm-" + UUID.randomUUID();

    @Override
    public String ownerId() {
        return ownerId;
    }
}
