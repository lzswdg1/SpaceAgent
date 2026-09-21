package com.spaceagent.platform.inference.domain;

/**
 * Domain port for encrypting and decrypting provider secrets. Framework/provider
 * SDK concerns remain in infrastructure.
 */
public interface ModelProviderSecretCipher {

    String encrypt(String plaintext);

    String decrypt(String encoded);
}
