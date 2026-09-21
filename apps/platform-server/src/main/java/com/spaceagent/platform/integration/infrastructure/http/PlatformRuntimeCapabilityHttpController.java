package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only view of capabilities executable by the current Runtime. */
@RestController
@RequestMapping("/api/v1/tooling/capabilities")
public class PlatformRuntimeCapabilityHttpController {

    private final RuntimeCapabilityCatalogApplicationApi catalogApi;

    public PlatformRuntimeCapabilityHttpController(
            RuntimeCapabilityCatalogApplicationApi catalogApi) {
        this.catalogApi = catalogApi;
    }

    @GetMapping
    public ApiResponse<RuntimeCapabilityCatalogApplicationApi.CapabilityCatalogView> catalog(
            Authentication authentication) {
        PlatformHttpSupport.userId(authentication);
        return ApiResponse.ok(catalogApi.catalog());
    }
}
