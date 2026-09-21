package com.spaceagent.platform.tooling.infrastructure;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;import com.spaceagent.shared.crypto.VersionedAesGcmCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.context.annotation.*;
@Configuration @EnableConfigurationProperties(McpToolingProperties.class)
public class McpToolingConfiguration{
 @Bean McpConnectionSecretCipher mcpConnectionSecretCipher(McpToolingProperties p){var c=new VersionedAesGcmCipher(p.getEncryptionKey(),p.getPreviousEncryptionKeys(),"PLATFORM_TOOLING_MCP_ENCRYPTION_KEY");return new McpConnectionSecretCipher(){public String encrypt(String v){return c.encrypt(v);}public String decrypt(String v){return c.decrypt(v);}};}
}
