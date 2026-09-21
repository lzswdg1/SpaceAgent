package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationManifestValidator;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/materializations")
public class PlatformLocalProjectMaterializationHttpController {
    private static final String TOKEN="X-SpaceAgent-Bridge-Token";
    private final LocalProjectMaterializationApplicationApi api;private final LocalWorkspaceBridgeApplicationApi bridges;
    public PlatformLocalProjectMaterializationHttpController(LocalProjectMaterializationApplicationApi api,LocalWorkspaceBridgeApplicationApi bridges){this.api=api;this.bridges=bridges;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LocalProjectMaterializationApplicationApi.SessionResult> start(@PathVariable String projectId,
            @RequestHeader(TOKEN) @Size(max=256) String token,@Valid @RequestBody StartRequest r,Authentication auth){PlatformHttpSupport.requireWrite(auth);
        String tenant=PlatformHttpSupport.tenantId(auth),owner=PlatformHttpSupport.userId(auth);
        var bridge=bridges.heartbeat(new HeartbeatLocalWorkspaceBridgeCommand(tenant,owner,r.bridgeId(),token));
        return ApiResponse.ok(api.start(new LocalProjectMaterializationApplicationApi.StartCommand(tenant,owner,projectId,bridge.id(),bridge.deviceId(),bridge.rootHandle(),r.requestId())));}
    @PutMapping("/{sessionId}/manifest") public ApiResponse<?> manifest(@PathVariable String projectId,@PathVariable String sessionId,@RequestHeader(TOKEN) @Size(max=256) String token,@Valid @RequestBody ManifestRequest r,Authentication auth){PlatformHttpSupport.requireWrite(auth);return ApiResponse.ok(api.declareManifest(new LocalProjectMaterializationApplicationApi.ManifestCommand(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),projectId,sessionId,r.requestId(),r.manifestSha256(),token)));}
    @PutMapping(value="/{sessionId}/chunks",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) public ApiResponse<?> chunk(@PathVariable String projectId,@PathVariable String sessionId,@RequestHeader(TOKEN) @Size(max=256) String token,@RequestParam String requestId,@RequestParam String relativePath,@RequestParam long offset,@RequestParam long contentLength,@RequestParam String contentSha256,HttpServletRequest request,Authentication auth)throws IOException{PlatformHttpSupport.requireWrite(auth);if(contentLength<=0||contentLength>ProjectLocalMaterializationChunk.MAX_CONTENT_LENGTH)throw new BusinessException("Chunk exceeds transport limit",HttpStatus.PAYLOAD_TOO_LARGE,"MATERIALIZATION_CHUNK_TOO_LARGE");long declared=request.getContentLengthLong();if(declared>=0&&declared!=contentLength)throw new BusinessException("Chunk Content-Length mismatch",HttpStatus.BAD_REQUEST,"MATERIALIZATION_CHUNK_LENGTH_MISMATCH");return ApiResponse.ok(api.stageChunk(new LocalProjectMaterializationApplicationApi.ChunkCommand(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),projectId,sessionId,requestId,relativePath,offset,contentLength,contentSha256,token),new ExactLengthInputStream(request.getInputStream(),contentLength)));}
    @PostMapping("/{sessionId}/finalize") public ApiResponse<?> finalizeSnapshot(@PathVariable String projectId,@PathVariable String sessionId,@RequestHeader(TOKEN) @Size(max=256) String token,@Valid @RequestBody FinalizeRequest r,Authentication auth){PlatformHttpSupport.requireWrite(auth);var entries=r.entries().stream().map(e->new ProjectLocalMaterializationManifestValidator.Entry(e.relativePath(),ProjectLocalMaterializationManifestValidator.EntryKind.REGULAR_FILE,e.contentLength(),e.contentSha256())).toList();var manifest=new ProjectLocalMaterializationManifestValidator.Manifest(r.manifestSha256(),entries.size(),r.totalBytes(),entries);return ApiResponse.ok(api.finalizeSnapshot(new LocalProjectMaterializationApplicationApi.FinalizeCommand(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),projectId,sessionId,r.requestId(),r.manifestSha256(),manifest,token)));}
    public record StartRequest(@NotBlank String bridgeId,@NotBlank @Size(max=200) String requestId){}
    public record ManifestRequest(@NotBlank @Size(max=200) String requestId,@Pattern(regexp="sha256:[0-9a-f]{64}") String manifestSha256){}
    public record Entry(@NotBlank @Size(max=1024) String relativePath,@PositiveOrZero long contentLength,@Pattern(regexp="sha256:[0-9a-f]{64}") String contentSha256){}
    public record FinalizeRequest(@NotBlank @Size(max=200) String requestId,@Pattern(regexp="sha256:[0-9a-f]{64}") String manifestSha256,@PositiveOrZero long totalBytes,@NotNull @Size(max=100000) List<@Valid Entry> entries){}

    private static final class ExactLengthInputStream extends FilterInputStream {
        private long remaining;
        private boolean endVerified;
        private ExactLengthInputStream(InputStream input,long length){super(input);remaining=length;}
        @Override public int read()throws IOException{if(remaining==0)return verifyEnd();int value=super.read();if(value<0)throw mismatch();remaining--;return value;}
        @Override public int read(byte[] buffer,int offset,int length)throws IOException{if(remaining==0)return verifyEnd();int bounded=(int)Math.min(length,remaining);int read=super.read(buffer,offset,bounded);if(read<0)throw mismatch();remaining-=read;return read;}
        private int verifyEnd()throws IOException{if(endVerified)return -1;endVerified=true;if(super.read()!=-1)throw mismatch();return -1;}
        private static BusinessException mismatch(){return new BusinessException("Chunk body length mismatch",HttpStatus.BAD_REQUEST,"MATERIALIZATION_CHUNK_LENGTH_MISMATCH");}
    }
}
