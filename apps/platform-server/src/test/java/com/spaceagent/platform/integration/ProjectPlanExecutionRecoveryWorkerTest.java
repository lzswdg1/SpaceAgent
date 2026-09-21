package com.spaceagent.platform.integration;

import com.spaceagent.platform.integration.application.ProjectCodingCoordinator;
import com.spaceagent.platform.integration.application.ProjectPlanExecutionRecoveryWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectPlanExecutionRecoveryWorkerTest {
    @Test
    void pollRunsBoundedTransitionRecovery() {
        ProjectCodingCoordinator coordinator = mock(ProjectCodingCoordinator.class);
        ProjectPlanExecutionRecoveryWorker worker =
                new ProjectPlanExecutionRecoveryWorker(coordinator);

        worker.poll();

        verify(coordinator).recoverControlTransitions(50);
    }

    @Test
    void pollFailsClosedWithoutKillingScheduler() {
        ProjectCodingCoordinator coordinator = mock(ProjectCodingCoordinator.class);
        when(coordinator.recoverControlTransitions(50))
                .thenThrow(new IllegalStateException("fixture failure"));
        ProjectPlanExecutionRecoveryWorker worker =
                new ProjectPlanExecutionRecoveryWorker(coordinator);

        assertThatCode(worker::poll).doesNotThrowAnyException();
    }
}
