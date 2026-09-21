package com.spaceagent.admin.config;

import com.spaceagent.admin.platformclient.AdminPlatformClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminProductionConfigurationValidatorTest {

    @Test
    void standaloneAdminServerDefaultsToLoopback() throws Exception {
        String configuration = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(configuration)
                .contains("address: ${ADMIN_SERVER_ADDRESS:127.0.0.1}");
    }

    @Test
    void developmentModeAllowsExplicitLocalConfiguration() {
        AdminReleaseProperties release = new AdminReleaseProperties();
        assertThatCode(() -> validator(release, new AdminSecurityProperties(),
                new AdminBootstrapProperties(), new AdminPlatformClientProperties(),
                new MockEnvironment()).validate()).doesNotThrowAnyException();
    }

    @Test
    void trustedBetaAcceptsExplicitSecureConfiguration() {
        assertThatCode(() -> validator(secureRelease(), secureSecurity(), secureBootstrap(),
                securePlatformClient(), secureEnvironment()).validate()).doesNotThrowAnyException();
    }

    @Test
    void releaseRejectsDevelopmentOrMissingSecrets() {
        AdminSecurityProperties security = secureSecurity();
        security.setJwtSecret("local-dev-admin-jwt-secret-change-in-production");
        assertRejected(security, secureBootstrap(), securePlatformClient(), secureEnvironment());

        AdminPlatformClientProperties client = securePlatformClient();
        client.setJwtSecret("replace-with-system-admin-secret-placeholder");
        assertRejected(secureSecurity(), secureBootstrap(), client, secureEnvironment());

        MockEnvironment environment = secureEnvironment();
        environment.setProperty("spring.datasource.password", "short");
        assertRejected(secureSecurity(), secureBootstrap(), securePlatformClient(), environment);
    }

    @Test
    void releaseRejectsInsecureCookieTransportAndBootstrap() {
        AdminSecurityProperties security = secureSecurity();
        security.setCookieSecure(false);
        assertRejected(security, secureBootstrap(), securePlatformClient(), secureEnvironment());

        AdminPlatformClientProperties client = securePlatformClient();
        client.setAllowInsecureLocal(true);
        assertRejected(secureSecurity(), secureBootstrap(), client, secureEnvironment());

        client = securePlatformClient();
        client.setBaseUrl("http://127.0.0.1:9000");
        assertRejected(secureSecurity(), secureBootstrap(), client, secureEnvironment());

        AdminBootstrapProperties bootstrap = secureBootstrap();
        bootstrap.setPassword("replace-with-admin-password");
        assertRejected(secureSecurity(), bootstrap, securePlatformClient(), secureEnvironment());
    }

    private static void assertRejected(
            AdminSecurityProperties security,
            AdminBootstrapProperties bootstrap,
            AdminPlatformClientProperties client,
            MockEnvironment environment) {
        assertThatThrownBy(() -> validator(
                secureRelease(), security, bootstrap, client, environment).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    private static AdminProductionConfigurationValidator validator(
            AdminReleaseProperties release,
            AdminSecurityProperties security,
            AdminBootstrapProperties bootstrap,
            AdminPlatformClientProperties client,
            MockEnvironment environment) {
        return new AdminProductionConfigurationValidator(
                release, security, bootstrap, client, environment);
    }

    private static AdminReleaseProperties secureRelease() {
        AdminReleaseProperties release = new AdminReleaseProperties();
        release.setMode(AdminReleaseProperties.Mode.TRUSTED_BETA);
        return release;
    }

    private static AdminSecurityProperties secureSecurity() {
        AdminSecurityProperties security = new AdminSecurityProperties();
        security.setJwtSecret("admin-release-jwt-secret-32-characters-long");
        security.setCookieSecure(true);
        return security;
    }

    private static AdminBootstrapProperties secureBootstrap() {
        AdminBootstrapProperties bootstrap = new AdminBootstrapProperties();
        bootstrap.setLoginName("platform-administrator");
        bootstrap.setDisplayName("Platform Administrator");
        bootstrap.setPassword("Strong-Admin-Password-2026!");
        return bootstrap;
    }

    private static AdminPlatformClientProperties securePlatformClient() {
        AdminPlatformClientProperties client = new AdminPlatformClientProperties();
        client.setBaseUrl("https://platform.private.internal");
        client.setJwtSecret("system-admin-release-secret-32-characters-long");
        client.setAllowInsecureLocal(false);
        return client;
    }

    private static MockEnvironment secureEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.password", "Strong-Database-Password-2026!");
    }
}
