package com.spaceagent.platform.inference.infrastructure;

import com.spaceagent.platform.inference.domain.ModelCallClaimOwnerProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JvmModelCallClaimOwnerProvider implements ModelCallClaimOwnerProvider {

    private final String ownerId = "jvm-" + UUID.randomUUID();

    @Override
    public String ownerId() {
        return ownerId;
    }
}
