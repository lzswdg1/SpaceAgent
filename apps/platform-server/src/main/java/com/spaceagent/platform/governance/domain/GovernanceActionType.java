package com.spaceagent.platform.governance.domain;

/** Security-sensitive capability classes governed at Java side-effect boundaries. */
public enum GovernanceActionType {
    CODING_FILE_MUTATION,
    COMMAND_EXECUTION,
    AUTOMATION_TRIGGER,
    NETWORK_ACCESS,
    SOURCE_MERGE,
    AGENT_CONFIGURATION_CHANGE
}
