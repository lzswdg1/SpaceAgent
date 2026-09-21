package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.integration.application.PublicRecoveryProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class PublicRecoveryProjectionTest {
    @Test void publicCopyHidesExecutionRefWithoutChangingDurableSnapshot()throws Exception{
        var internal=new ObjectMapper().readTree("{\"context\":{\"workspace\":{\"branchName\":\"spaceagent/internal\",\"baseRef\":\"feature/user\"}},\"snapshotId\":\"stable\"}");
        var output=PublicRecoveryProjection.redact(internal);
        assertThat(output.toString()).doesNotContain("spaceagent/internal","branchName").contains("feature/user","stable","USER_REDACTED");
        assertThat(internal.toString()).contains("spaceagent/internal");
    }
}
