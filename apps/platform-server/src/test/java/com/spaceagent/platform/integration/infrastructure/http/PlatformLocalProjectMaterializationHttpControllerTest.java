package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.LocalWorkspaceBridgeState;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk;
import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import com.spaceagent.shared.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformLocalProjectMaterializationHttpControllerTest {
    @Test void startAuthenticatesBridgeAndPassesOnlyResolvedOpaqueBinding() {
        var api=mock(LocalProjectMaterializationApplicationApi.class);var bridges=mock(LocalWorkspaceBridgeApplicationApi.class);
        var auth=new UsernamePasswordAuthenticationToken("owner",null,List.of());auth.setDetails(new TenantAuthenticationDetails("tenant","MEMBER"));
        var bridge=new LocalWorkspaceBridgeView("00000000-0000-4000-8000-000000000001","Laptop","device","root_12345678","brg_prefix",LocalWorkspaceBridgeState.ACTIVE,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,null);
        when(bridges.heartbeat(any())).thenReturn(bridge);when(api.start(any())).thenReturn(new LocalProjectMaterializationApplicationApi.SessionResult("session","OPEN",null,Instant.EPOCH,1));
        var controller=new PlatformLocalProjectMaterializationHttpController(api,bridges);
        assertThat(controller.start("project","secret-token",new PlatformLocalProjectMaterializationHttpController.StartRequest(bridge.id(),"request"),auth).data().sessionId()).isEqualTo("session");
        verify(bridges).heartbeat(argThat(c->c.rawToken().equals("secret-token")&&c.userId().equals("owner")));
        verify(api).start(argThat(c->c.bridgeRootHandle().equals("root_12345678")&&c.bridgeDeviceId().equals("device")));
    }

    @Test void chunkStreamsExactBodyAndRejectsHeaderMismatchOrOversize()throws Exception{
        var api=mock(LocalProjectMaterializationApplicationApi.class);var controller=new PlatformLocalProjectMaterializationHttpController(api,mock(LocalWorkspaceBridgeApplicationApi.class));MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();var auth=auth();byte[] body="bounded".getBytes(StandardCharsets.UTF_8);String hash=hash(body);
        when(api.stageChunk(any(),any())).thenAnswer(call->{var command=call.getArgument(0,LocalProjectMaterializationApplicationApi.ChunkCommand.class);var input=call.getArgument(1,java.io.InputStream.class);assertThat(input.readAllBytes()).isEqualTo(body);return new LocalProjectMaterializationApplicationApi.ChunkResult(command.requestId(),command.contentSha256(),command.contentLength());});
        mvc.perform(put("/api/v1/projects/project/materializations/session/chunks").principal(auth).header("X-SpaceAgent-Bridge-Token","token").contentType(MediaType.APPLICATION_OCTET_STREAM).param("requestId","request").param("relativePath","src/App.java").param("offset","0").param("contentLength",Long.toString(body.length)).param("contentSha256",hash).content(body)).andExpect(status().isOk());
        verify(api).stageChunk(any(),any());
        mvc.perform(put("/api/v1/projects/project/materializations/session/chunks").principal(auth).header("X-SpaceAgent-Bridge-Token","token").contentType(MediaType.APPLICATION_OCTET_STREAM).param("requestId","short").param("relativePath","src/App.java").param("offset","0").param("contentLength","1").param("contentSha256",hash).content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/projects/project/materializations/session/chunks").principal(auth).header("X-SpaceAgent-Bridge-Token","token").contentType(MediaType.APPLICATION_OCTET_STREAM).param("requestId","large").param("relativePath","src/App.java").param("offset","0").param("contentLength",Long.toString(ProjectLocalMaterializationChunk.MAX_CONTENT_LENGTH+1)).param("contentSha256",hash).content(new byte[0])).andExpect(status().isPayloadTooLarge());
    }

    @Test void chunkWithoutContentLengthStillCannotReadPastDeclaredBytes()throws Exception{
        var api=mock(LocalProjectMaterializationApplicationApi.class);var controller=new PlatformLocalProjectMaterializationHttpController(api,mock(LocalWorkspaceBridgeApplicationApi.class));var auth=auth();byte[] body="two".getBytes(StandardCharsets.UTF_8);String hash=hash(body);var request=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return -1;}};request.setContent(body);
        when(api.stageChunk(any(),any())).thenAnswer(call->{call.getArgument(1,java.io.InputStream.class).readAllBytes();return null;});
        assertThatThrownBy(()->controller.chunk("project","session","token","missing-header","a.txt",0,2,hash,request,auth)).isInstanceOfSatisfying(com.spaceagent.shared.exception.BusinessException.class,error->assertThat(error.getCode()).isEqualTo("MATERIALIZATION_CHUNK_LENGTH_MISMATCH"));
        verify(api).stageChunk(any(),any());
    }

    private static UsernamePasswordAuthenticationToken auth(){var auth=new UsernamePasswordAuthenticationToken("owner",null,List.of());auth.setDetails(new TenantAuthenticationDetails("tenant","MEMBER"));return auth;}
    private static String hash(byte[] value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}
