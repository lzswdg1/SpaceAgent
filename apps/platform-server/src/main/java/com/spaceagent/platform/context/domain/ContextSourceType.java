package com.spaceagent.platform.context.domain;

/**
 * Categories of context sources that can be compiled into a
 * {@link ContextPackage}.
 */
public enum ContextSourceType {
    USER,
    PROJECT,
    TASK,
    REPOSITORY,
    CONVERSATION,
    MEMORY,
    KNOWLEDGE,
    TOOL,
    SKILL,
    SYSTEM,
    AGENT_DEFINITION
}
