package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResourceObservationPayloadPolicyTest {
    @Test void permitsNullableCountersButRejectsExtraPayloadAndBadNumbers() throws Exception {
        var json=new ObjectMapper();
        ResourceObservationPayloadPolicy.validate(json.readTree("{\"executionId\":\"exec\",\"toolCallId\":\"call\",\"resourceMetrics\":{\"cpuUsageNanos\":null,\"coverage\":\"UNAVAILABLE\"}}"));
        for(String value:new String[]{"{\"cpuUsageNanos\":-1}","{\"cpuUsageNanos\":\"secret\"}","{\"prompt\":\"secret\"}"})
            assertThatThrownBy(()->ResourceObservationPayloadPolicy.validate(json.readTree("{\"executionId\":\"exec\",\"toolCallId\":\"call\",\"resourceMetrics\":"+value+"}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
