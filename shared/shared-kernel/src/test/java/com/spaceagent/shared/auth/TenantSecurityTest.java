package com.spaceagent.shared.auth;

import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantSecurityTest {

    @Test
    void usesSignedTenantDetailsWhenPresent() {
        var authentication = new UsernamePasswordAuthenticationToken("user-1", null, List.of());
        authentication.setDetails(new TenantAuthenticationDetails("tenant-1", "MEMBER"));

        assertEquals("tenant-1", TenantSecurity.requireTenantId(authentication));
        assertEquals("MEMBER", TenantSecurity.tenantRole(authentication));
        assertDoesNotThrow(() -> TenantSecurity.requireWrite(authentication));
        assertThrows(BusinessException.class, () -> TenantSecurity.requireAdmin(authentication));
    }

    @Test
    void fallsBackToPersonalTenantForLegacyAuthenticatedCalls() {
        var authentication = new UsernamePasswordAuthenticationToken("user-1", null, List.of());

        assertEquals("user-1", TenantSecurity.requireTenantId(authentication));
        assertEquals("OWNER", TenantSecurity.tenantRole(authentication));
    }

    @Test
    void rejectsUnauthenticatedCalls() {
        var authentication = new UsernamePasswordAuthenticationToken("user-1", null);

        assertThrows(BusinessException.class, () -> TenantSecurity.requireTenantId(authentication));
    }
}
