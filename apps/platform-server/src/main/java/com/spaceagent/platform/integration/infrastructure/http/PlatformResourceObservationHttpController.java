package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.RuntimeResourceObservationApi;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.*;

@RestController
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PlatformResourceObservationHttpController {
    private final RuntimeResourceObservationApi resources;
    public PlatformResourceObservationHttpController(RuntimeResourceObservationApi resources){this.resources=resources;}
    @GetMapping("/internal/system-admin/v1/resource-observations")
    public ApiResponse<RuntimeResourceObservationApi.Summary> administration(@RequestParam(required=false) String organizationId,
            @RequestParam(required=false) String userId,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to){
        Instant end=to==null?Instant.now():to;return ApiResponse.ok(resources.summary(organizationId,userId,from==null?end.minus(Duration.ofDays(1)):from,end));
    }
    @GetMapping("/api/v1/monitoring/resource-observations")
    public ApiResponse<RuntimeResourceObservationApi.Summary> tenant(@RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant to,Authentication authentication){
        Instant end=to==null?Instant.now():to;return ApiResponse.ok(resources.summary(PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),from==null?end.minus(Duration.ofDays(1)):from,end));
    }
}
