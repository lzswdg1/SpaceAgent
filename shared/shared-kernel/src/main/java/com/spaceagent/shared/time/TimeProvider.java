package com.spaceagent.shared.time;

import java.time.Instant;

public interface TimeProvider {

    Instant now();
}
