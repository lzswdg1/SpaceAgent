package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.inference.api.ModelPricingApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/provider-models/{providerModelId}/prices")
public class PlatformModelPricingHttpController {
    private final ModelPricingApplicationApi pricing;
    public PlatformModelPricingHttpController(ModelPricingApplicationApi pricing){this.pricing=pricing;}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<ModelPricingApplicationApi.PriceView> create(@PathVariable String providerModelId,@Valid @RequestBody Request r,Authentication a){PlatformHttpSupport.requireWrite(a);return ApiResponse.ok(pricing.createPrice(new ModelPricingApplicationApi.CreatePriceCommand(PlatformHttpSupport.tenantId(a),PlatformHttpSupport.userId(a),providerModelId,r.inputMicrosPerMillionTokens(),r.outputMicrosPerMillionTokens(),r.effectiveFrom(),r.effectiveUntil())));}
    @GetMapping public ApiResponse<List<ModelPricingApplicationApi.PriceView>> list(@PathVariable String providerModelId,Authentication a){return ApiResponse.ok(pricing.prices(PlatformHttpSupport.tenantId(a),PlatformHttpSupport.userId(a),providerModelId));}
    public record Request(@PositiveOrZero long inputMicrosPerMillionTokens,@PositiveOrZero long outputMicrosPerMillionTokens,Instant effectiveFrom,Instant effectiveUntil){}
}
