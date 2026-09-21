package com.spaceagent.platform.runtime.domain;

import java.util.List;
import java.util.Optional;

public interface AgentDelegationRepository {

    void save(AgentDelegation delegation);

    Optional<AgentDelegation> findById(String id);

    List<AgentDelegation> findByParentRunId(String parentRunId);
}
