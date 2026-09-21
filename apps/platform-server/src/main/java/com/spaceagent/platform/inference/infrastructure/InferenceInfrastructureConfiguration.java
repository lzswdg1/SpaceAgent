package com.spaceagent.platform.inference.infrastructure;

import com.spaceagent.platform.inference.domain.ModelProviderSecretCipher;
import com.spaceagent.shared.crypto.VersionedAesGcmCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires inference-specific secret encryption without leaking provider SDK or
 * persistence concerns into the domain package.
 */
@Configuration
@EnableConfigurationProperties(InferenceProperties.class)
public class InferenceInfrastructureConfiguration {

    @Bean
    public ModelProviderSecretCipher modelProviderSecretCipher(InferenceProperties properties) {
        VersionedAesGcmCipher cipher = new VersionedAesGcmCipher(
                properties.getModelProviderEncryptionKey(),
                properties.getModelProviderPreviousEncryptionKeys(),
                "PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY");
        return new ModelProviderSecretCipher() {
            @Override
            public String encrypt(String plaintext) {
                return cipher.encrypt(plaintext);
            }

            @Override
            public String decrypt(String encoded) {
                return cipher.decrypt(encoded);
            }
        };
    }
}
