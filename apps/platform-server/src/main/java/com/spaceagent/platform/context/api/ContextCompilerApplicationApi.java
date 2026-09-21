package com.spaceagent.platform.context.api;

import com.spaceagent.platform.context.domain.ContextPackage;

/**
 * Public context application API. ContextPackage is a compiled runtime value; callers
 * provide typed contributions and receive a budgeted, prioritized, deduplicated package.
 */
public interface ContextCompilerApplicationApi {

    ContextPackage compile(CompileContextCommand command);
}
