package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "platform.sandbox.mode=http",
        "platform.sandbox.base-url=http://sandbox.test:9200",
        "platform.sandbox.internal-token=sandbox-http-wiring-token-0123456789-abcdef"
})
class HttpSandboxSpringWiringTest {
    @Autowired
    private SandboxExecutionGateway gateway;

    @Test
    void injectsTheHttpGatewayInHttpMode() {
        assertThat(gateway).isInstanceOf(HttpSandboxExecutionGateway.class);
    }
}
