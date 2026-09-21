package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.inference.api.ProviderHealthProbeApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/model-providers/{providerId}/health-observations")
public class PlatformProviderHealthHttpController {
    private final ProviderHealthProbeApplicationApi probes;

    public PlatformProviderHealthHttpController(ProviderHealthProbeApplicationApi probes) {
        this.probes = probes;
    }

    @GetMapping
    public ApiResponse<List<ProviderHealthProbeApplicationApi.ObservationView>> observations(
            @PathVariable String providerId,
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication) {
        return ApiResponse.ok(probes.observations(
                PlatformHttpSupport.tenantId(authentication), providerId, limit));
    }
}
