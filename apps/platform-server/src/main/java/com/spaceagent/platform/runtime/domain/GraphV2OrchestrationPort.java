package com.spaceagent.platform.runtime.domain;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
public interface GraphV2OrchestrationPort { GraphV2Contract.Result transition(GraphV2Contract.Request request); }
