package com.spaceagent.platform.runtime.domain;
public record SupervisorRunPolicy(String agentRunId,String tenantId,String ownerId,SupervisorPolicy policy,long revision){public SupervisorRunPolicy{if(agentRunId==null||agentRunId.isBlank()||tenantId==null||tenantId.isBlank()||ownerId==null||ownerId.isBlank()||policy==null||revision<1)throw new IllegalArgumentException("supervisor run policy");}}
