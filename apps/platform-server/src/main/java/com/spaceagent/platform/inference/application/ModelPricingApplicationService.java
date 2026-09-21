package com.spaceagent.platform.inference.application;

import com.spaceagent.platform.inference.api.ModelPricingApplicationApi;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ModelPricingApplicationService implements ModelPricingApplicationApi {
    private final ModelPriceRepository prices;private final InferenceProviderRepository providers;private final IdGenerator ids;private final TimeProvider time;
    public ModelPricingApplicationService(ModelPriceRepository p,InferenceProviderRepository r,IdGenerator i,TimeProvider t){prices=p;providers=r;ids=i;time=t;}
    @Transactional public PriceView createPrice(CreatePriceCommand c){ProviderModel model=providers.findModelById(c.providerModelId()).orElseThrow(()->notFound());ModelProvider provider=providers.findProviderByTenantAndId(c.tenantId(),model.providerId()).filter(v->v.ownerId().equals(c.userId())).orElseThrow(()->notFound());if(c.inputMicrosPerMillionTokens()<0||c.outputMicrosPerMillionTokens()<0)throw invalid();Instant from=c.effectiveFrom()==null?time.now():c.effectiveFrom(),until=c.effectiveUntil();if(until!=null&&!until.isAfter(from))throw invalid();int version=prices.nextVersion(model.id());if(prices.overlaps(model.id(),from,until))throw new BusinessException("Model price window overlaps",HttpStatus.CONFLICT,"MODEL_PRICE_CONFLICT");ModelPrice v=new ModelPrice(ids.nextId(),provider.tenantId(),model.id(),version,c.inputMicrosPerMillionTokens(),c.outputMicrosPerMillionTokens(),"USD",from,until,c.userId(),time.now());prices.save(v);return view(v);}
    @Transactional(readOnly=true) public List<PriceView> prices(String t,String u,String model){ProviderModel m=providers.findModelById(model).orElseThrow(()->notFound());providers.findProviderByTenantAndId(t,m.providerId()).orElseThrow(()->notFound());return prices.findByProviderModelId(model).stream().map(ModelPricingApplicationService::view).toList();}
    private static PriceView view(ModelPrice v){return new PriceView(v.id(),v.providerModelId(),v.version(),v.inputMicrosPerMillionTokens(),v.outputMicrosPerMillionTokens(),v.currency(),v.effectiveFrom(),v.effectiveUntil(),v.createdAt());}private static BusinessException invalid(){return new BusinessException("Model price is invalid",HttpStatus.BAD_REQUEST,"MODEL_PRICE_INVALID");}private static BusinessException notFound(){return new BusinessException("Provider model not found",HttpStatus.NOT_FOUND,"PROVIDER_MODEL_NOT_FOUND");}
}
