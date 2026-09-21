package com.spaceagent.platform.artifact;

import com.spaceagent.platform.artifact.api.*;
import com.spaceagent.platform.artifact.application.*;
import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.platform.artifact.infrastructure.*;
import com.spaceagent.platform.artifact.infrastructure.memory.InMemoryArtifactObjectRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ArtifactPublicationInterlockTest {
    @TempDir Path root;
    @Test void pendingDeletionRejectsNewHoldsAndReferencesAndCannotDeleteANewPublication() throws Exception {
        var f = new Fixture(root);
        var first = f.publish("user", "first", f.now);
        f.service.requestDeletion(new ArtifactObjectApplicationApi.RequestDeletionCommand("tenant", "user", first.id(), first.revision(), "delete"));
        assertThatThrownBy(() -> f.service.placeHold(new ArtifactObjectApplicationApi.PlaceHoldCommand("tenant", "user", first.id(), hash(), "hold")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> f.repository.insertReference(new ArtifactObjectReference(UUID.randomUUID().toString(), "tenant", first.id(),
                ArtifactObjectReference.OwnerType.PROJECT, "project", "test", ArtifactObjectReference.State.ACTIVE, 1, f.now, null)))
                .isInstanceOf(ArtifactObjectLifecycleConflictException.class);
        var next = f.publish("user", "next", f.now.plusSeconds(3600));
        assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(next.state()).isEqualTo("READY");
        assertThat(next.retainUntil()).isEqualTo(f.now.plusSeconds(3600));
        f.maintenance.runDeletionOnce("worker", 60);
        assertThat(f.service.read(new ArtifactObjectApplicationApi.ReadObjectQuery("tenant", "user", next.id(), 0, 5)).contentBase64())
                .isEqualTo(Base64.getEncoder().encodeToString("hello".getBytes()));
    }

    @Test void legalHoldBeforeDeletionIsHonoredAndEqualContentDoesNotSharePrivateAccess() throws Exception {
        var f = new Fixture(root);
        var first = f.publish("user", "first", f.now);
        f.service.placeHold(new ArtifactObjectApplicationApi.PlaceHoldCommand("tenant", "user", first.id(), hash(), "hold"));
        assertThatThrownBy(() -> f.service.requestDeletion(new ArtifactObjectApplicationApi.RequestDeletionCommand(
                "tenant", "user", first.id(), first.revision(), "delete"))).isInstanceOf(IllegalStateException.class);
        var other = f.publish("other", "first", f.now.plusSeconds(60));
        assertThat(other.id()).isNotEqualTo(first.id());
        assertThatThrownBy(() -> f.service.get(new ArtifactObjectApplicationApi.GetObjectQuery("tenant", "other", first.id())))
                .isInstanceOf(BusinessException.class);
        assertThat(f.service.get(new ArtifactObjectApplicationApi.GetObjectQuery("tenant", "other", other.id())).state()).isEqualTo("READY");
    }

    private static String hash() throws Exception { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("hello".getBytes())); }
    private static class Fixture {
        final Instant now = Instant.parse("2026-09-15T00:00:00Z");
        final AtomicInteger sequence = new AtomicInteger();
        final InMemoryArtifactObjectRepository repository = new InMemoryArtifactObjectRepository(() -> now);
        final ArtifactObjectMaintenanceApplicationService maintenance;
        final ManagedArtifactObjectApplicationService service;
        Fixture(Path root) {
            var storage = new LocalArtifactObjectStorageGateway(root);
            maintenance = new ArtifactObjectMaintenanceApplicationService(repository, storage, () -> UUID.randomUUID().toString());
            service = new ManagedArtifactObjectApplicationService(repository, storage, (t, a, type, id) -> false,
                    maintenance, (t, a) -> true, () -> UUID.randomUUID().toString(), new ArtifactObjectStorageProperties());
        }
        ArtifactObjectApplicationApi.ObjectView publish(String user, String request, Instant retention) throws Exception {
            var stage = service.beginStaging(new ArtifactObjectApplicationApi.BeginStagingCommand("tenant", user, request, hash(), 5, "text/plain", 300));
            service.upload(new ArtifactObjectApplicationApi.UploadChunkCommand("tenant", user, stage.id(), 0, Base64.getEncoder().encodeToString("hello".getBytes())));
            var verified = service.verify(new ArtifactObjectApplicationApi.VerifyStagingCommand("tenant", user, stage.id(), stage.revision()));
            return service.publish(new ArtifactObjectApplicationApi.PublishStagingCommand("tenant", user, stage.id(), verified.revision(), retention, true));
        }
    }
}
