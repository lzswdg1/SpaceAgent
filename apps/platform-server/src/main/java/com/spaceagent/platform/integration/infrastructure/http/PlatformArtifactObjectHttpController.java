package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.artifact.api.ArtifactObjectApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactObjectReference;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/artifact-objects")
public class PlatformArtifactObjectHttpController {
    private final ArtifactObjectApplicationApi objects;public PlatformArtifactObjectHttpController(ArtifactObjectApplicationApi objects){this.objects=objects;}
    @PostMapping("/staging") public ApiResponse<ArtifactObjectApplicationApi.StagingView> begin(@Valid @RequestBody BeginRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.beginStaging(new ArtifactObjectApplicationApi.BeginStagingCommand(tenant(a),user(a),r.requestId(),r.expectedSha256(),r.expectedBytes(),r.mediaType(),r.ttlSeconds())));}
    @GetMapping("/staging/{id}") public ApiResponse<ArtifactObjectApplicationApi.StagingView> staging(@PathVariable String id,Authentication a){return ApiResponse.ok(objects.getStaging(new ArtifactObjectApplicationApi.GetStagingQuery(tenant(a),user(a),id)));}
    @PostMapping("/staging/{id}/chunks") public ApiResponse<ArtifactObjectApplicationApi.StagingView> upload(@PathVariable String id,@Valid @RequestBody ChunkRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.upload(new ArtifactObjectApplicationApi.UploadChunkCommand(tenant(a),user(a),id,r.expectedOffset(),r.chunkBase64())));}
    @PostMapping("/staging/{id}/verify") public ApiResponse<ArtifactObjectApplicationApi.StagingView> verify(@PathVariable String id,@Valid @RequestBody RevisionRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.verify(new ArtifactObjectApplicationApi.VerifyStagingCommand(tenant(a),user(a),id,r.expectedRevision())));}
    @PostMapping("/staging/{id}/publish") public ApiResponse<ArtifactObjectApplicationApi.ObjectView> publish(@PathVariable String id,@Valid @RequestBody PublishRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.publish(new ArtifactObjectApplicationApi.PublishStagingCommand(tenant(a),user(a),id,r.expectedRevision(),r.retainUntil(),r.deleteWhenUnreferenced())));}
    @GetMapping("/{id}") public ApiResponse<ArtifactObjectApplicationApi.ObjectView> get(@PathVariable String id,Authentication a){return ApiResponse.ok(objects.get(new ArtifactObjectApplicationApi.GetObjectQuery(tenant(a),user(a),id)));}
    @PostMapping("/{id}/read") public ApiResponse<ArtifactObjectApplicationApi.ObjectBytesView> read(@PathVariable String id,@Valid @RequestBody ReadRequest r,Authentication a){return ApiResponse.ok(objects.read(new ArtifactObjectApplicationApi.ReadObjectQuery(tenant(a),user(a),id,r.offset(),r.maximumBytes())));}
    @PostMapping("/{id}/download-capabilities") public ApiResponse<ArtifactObjectApplicationApi.DownloadCapabilityView> download(@PathVariable String id,@Valid @RequestBody DownloadRequest r,Authentication a){return ApiResponse.ok(objects.createDownloadCapability(new ArtifactObjectApplicationApi.DownloadCapabilityCommand(tenant(a),user(a),id,r.ttlSeconds())));}
    @PostMapping("/{id}/references") public ApiResponse<ArtifactObjectApplicationApi.ReferenceView> attach(@PathVariable String id,@Valid @RequestBody AttachRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.attach(new ArtifactObjectApplicationApi.AttachReferenceCommand(tenant(a),user(a),id,r.ownerType(),r.ownerResourceId(),r.purpose(),r.requestId())));}
    @PostMapping("/references/{id}/release") public ApiResponse<ArtifactObjectApplicationApi.ReferenceView> release(@PathVariable String id,@Valid @RequestBody RevisionRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.release(new ArtifactObjectApplicationApi.ReleaseReferenceCommand(tenant(a),user(a),id,r.expectedRevision())));}
    @PostMapping("/{id}/legal-holds") public ApiResponse<ArtifactObjectApplicationApi.HoldView> hold(@PathVariable String id,@Valid @RequestBody HoldRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.placeHold(new ArtifactObjectApplicationApi.PlaceHoldCommand(tenant(a),user(a),id,r.reasonSha256(),r.requestId())));}
    @PostMapping("/legal-holds/{id}/release") public ApiResponse<ArtifactObjectApplicationApi.HoldView> releaseHold(@PathVariable String id,@Valid @RequestBody RevisionRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.releaseHold(new ArtifactObjectApplicationApi.ReleaseHoldCommand(tenant(a),user(a),id,r.expectedRevision())));}
    @PostMapping("/{id}/delete") public ApiResponse<ArtifactObjectApplicationApi.ObjectView> delete(@PathVariable String id,@Valid @RequestBody DeleteRequest r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(objects.requestDeletion(new ArtifactObjectApplicationApi.RequestDeletionCommand(tenant(a),user(a),id,r.expectedRevision(),r.requestId())));}
    private static String tenant(Authentication a){return PlatformHttpSupport.tenantId(a);}private static String user(Authentication a){return PlatformHttpSupport.userId(a);}
    public record BeginRequest(@NotBlank @Size(max=120) String requestId,@Pattern(regexp="sha256:[0-9a-f]{64}") String expectedSha256,@Min(0) @Max(5368709120L) long expectedBytes,@NotBlank @Size(max=160) String mediaType,@Min(60) @Max(3600) int ttlSeconds){}
    public record ChunkRequest(@Min(0) long expectedOffset,@NotBlank @Size(max=1400000) String chunkBase64){}
    public record RevisionRequest(@Positive long expectedRevision){}
    public record PublishRequest(@Positive long expectedRevision,@NotNull Instant retainUntil,boolean deleteWhenUnreferenced){}
    public record ReadRequest(@Min(0) long offset,@Min(1) @Max(1048576) int maximumBytes){}
    public record DownloadRequest(@Min(1) @Max(300) int ttlSeconds){}
    public record AttachRequest(@NotNull ArtifactObjectReference.OwnerType ownerType,@NotBlank @Size(max=200) String ownerResourceId,@NotBlank @Size(max=200) String purpose,@NotBlank @Size(max=120) String requestId){}
    public record HoldRequest(@Pattern(regexp="sha256:[0-9a-f]{64}") String reasonSha256,@NotBlank @Size(max=120) String requestId){}
    public record DeleteRequest(@Positive long expectedRevision,@NotBlank @Size(max=120) String requestId){}
}
