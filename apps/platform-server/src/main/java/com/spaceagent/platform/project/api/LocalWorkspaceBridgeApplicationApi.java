package com.spaceagent.platform.project.api;

import java.util.List;

public interface LocalWorkspaceBridgeApplicationApi {

    CreatedLocalWorkspaceBridgeView register(RegisterLocalWorkspaceBridgeCommand command);

    LocalWorkspaceBridgeView heartbeat(HeartbeatLocalWorkspaceBridgeCommand command);

    List<LocalWorkspaceBridgeView> list(ListLocalWorkspaceBridgesQuery query);

    LocalWorkspaceBridgeView resolve(ResolveLocalWorkspaceBridgeQuery query);

    void revoke(RevokeLocalWorkspaceBridgeCommand command);
}
