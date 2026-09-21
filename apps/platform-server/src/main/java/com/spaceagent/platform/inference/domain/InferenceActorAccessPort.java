package com.spaceagent.platform.inference.domain;
/** Owner-neutral current identity check; integration resolves it through Identity's public API. */
public interface InferenceActorAccessPort {boolean active(String tenantId,String actorId);}
