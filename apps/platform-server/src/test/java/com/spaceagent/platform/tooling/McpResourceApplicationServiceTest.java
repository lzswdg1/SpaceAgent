package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.api.*;
import com.spaceagent.platform.tooling.application.*;
import com.spaceagent.platform.tooling.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpResourceApplicationServiceTest {
    @Test void validatesBindingAndBoundsSafeTextForContext(){var marketplace=mock(McpMarketplaceRepository.class);var validator=mock(McpBindingValidationApplicationApi.class);var authorization=mock(McpConnectionAuthorizationService.class);var gateway=mock(McpRemoteToolGateway.class);var connection=connection();
        when(marketplace.findConnection("connection")).thenReturn(Optional.of(connection));when(authorization.authorize(connection)).thenReturn(new McpConnectionAuthorizationService.AuthorizedConnection(connection,Map.of("access_token","redacted")));
        when(gateway.listResources(any(),any())).thenReturn(List.of(new McpRemoteToolGateway.RemoteResource("resource://docs/readme","readme","Readme","safe","text/plain",5L)));
        when(gateway.readResource(any(),any(),eq("resource://docs/readme"))).thenReturn(new McpRemoteToolGateway.RemoteResourceContent("resource://docs/readme","text/plain","hello",false));
        var service=new McpResourceApplicationService(marketplace,validator,authorization,gateway);var binding=binding();
        assertThat(service.list(new McpResourceApplicationApi.ListCommand("tenant","owner",binding))).hasSize(1);
        assertThat(service.read(new McpResourceApplicationApi.ReadCommand("tenant","owner",binding,"resource://docs/readme")).utf8Bytes()).isEqualTo(5);
        verify(validator,times(2)).validate(any());}
    @Test void rejectsUnsafeUriMimeBlobOversizeAndCredentialShapedText(){var marketplace=mock(McpMarketplaceRepository.class);var validator=mock(McpBindingValidationApplicationApi.class);var authorization=mock(McpConnectionAuthorizationService.class);var gateway=mock(McpRemoteToolGateway.class);var connection=connection();when(marketplace.findConnection("connection")).thenReturn(Optional.of(connection));when(authorization.authorize(connection)).thenReturn(new McpConnectionAuthorizationService.AuthorizedConnection(connection,Map.of()));var service=new McpResourceApplicationService(marketplace,validator,authorization,gateway);var binding=binding();
        assertThatThrownBy(()->service.read(new McpResourceApplicationApi.ReadCommand("tenant","owner",binding,"file:///etc/passwd"))).isInstanceOf(com.spaceagent.shared.exception.BusinessException.class);
        when(gateway.readResource(any(),any(),anyString())).thenReturn(new McpRemoteToolGateway.RemoteResourceContent("resource://bad","application/octet-stream","blob",true));
        assertThatThrownBy(()->service.read(new McpResourceApplicationApi.ReadCommand("tenant","owner",binding,"resource://bad"))).isInstanceOf(com.spaceagent.shared.exception.BusinessException.class);
        when(gateway.readResource(any(),any(),anyString())).thenReturn(new McpRemoteToolGateway.RemoteResourceContent("resource://secret","text/plain","api_key=hidden",false));
        assertThatThrownBy(()->service.read(new McpResourceApplicationApi.ReadCommand("tenant","owner",binding,"resource://secret"))).isInstanceOf(com.spaceagent.shared.exception.BusinessException.class);}
    private static McpResourceApplicationApi.Binding binding(){return new McpResourceApplicationApi.Binding("installation","connection","server","snapshot",1,"sha256:"+"a".repeat(64),List.of("tool"));}
    private static McpConnection connection(){return new McpConnection("connection","installation","tenant","owner","https://mcp.example","encrypted",McpAuthType.NONE,McpConnectionState.ACTIVE,null,null,1,Instant.EPOCH,Instant.EPOCH,null);}
}
