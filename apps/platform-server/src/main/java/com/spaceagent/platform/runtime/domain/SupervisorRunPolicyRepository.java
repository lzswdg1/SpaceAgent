package com.spaceagent.platform.runtime.domain;
import java.util.Optional;
public interface SupervisorRunPolicyRepository {Optional<SupervisorRunPolicy> find(String agentRunId);boolean insertIfAbsent(SupervisorRunPolicy policy);}
