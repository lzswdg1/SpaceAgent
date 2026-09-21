package com.spaceagent.platform.inference.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.EmbeddingBatchApplicationApi;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.security.MessageDigest;

@Service
public class EmbeddingBatchApplicationService implements EmbeddingBatchApplicationApi {
    private final InferenceProviderRepository providers;
    private final ModelProviderEndpointPolicy endpoints;
    private final EmbeddingCallRepository calls;
    private final InferenceBudgetRepository budgets;
    private final ModelPriceRepository prices;
    private final EmbeddingBatchExecutor executor;
    private final ModelProviderSecretCipher cipher;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;
    private ModelPoolRepository pools;
    @org.springframework.beans.factory.annotation.Autowired
    public void modelPools(ModelPoolRepository pools){this.pools=pools;}
    public EmbeddingBatchApplicationService(InferenceProviderRepository providers,ModelProviderEndpointPolicy endpoints,
            EmbeddingCallRepository calls,InferenceBudgetRepository budgets,ModelPriceRepository prices,EmbeddingBatchExecutor executor,
            ModelProviderSecretCipher cipher,ObjectMapper json,IdGenerator ids,TimeProvider time) {
        this.providers=providers; this.endpoints=endpoints; this.calls=calls; this.budgets=budgets; this.prices=prices;
        this.executor=executor; this.cipher=cipher; this.json=json; this.ids=ids; this.time=time;
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result embed(Request r) {
        validateIdentity(r);
        if (r.inputs().size() > EmbeddingModelCapabilities.forModel(r.modelId()).maximumItems())
            throw invalid(HttpStatus.BAD_REQUEST, "EMBEDDING_MODEL_BATCH_LIMIT_EXCEEDED");
        var provider=providers.findProviderByTenantAndId(r.tenantId(),r.providerId())
                .filter(p->p.enabled() && authorized(r,p) && p.encryptedApiKey()!=null && !p.encryptedApiKey().isBlank())
                .orElseThrow(()->invalid(HttpStatus.NOT_FOUND,"EMBEDDING_PROVIDER_UNAVAILABLE"));
        var model=providers.findModelsByProviderId(provider.id()).stream().filter(m->m.modelId().equals(r.modelId())).findFirst()
                .orElseThrow(()->invalid(HttpStatus.NOT_FOUND,"EMBEDDING_MODEL_UNAVAILABLE"));
        endpoints.validateAndNormalize(provider.baseUrl());
        if(!digest(List.of(provider.id(),provider.providerType(),provider.baseUrl(),provider.authType())).equals(r.providerFingerprint()))
            throw invalid(HttpStatus.CONFLICT,"EMBEDDING_SPACE_CHANGED");
        int estimated;
        try { estimated=EmbeddingBounds.validate(r.inputs(),r.dimensions(),model.maxContextTokens()); }
        catch(IllegalArgumentException e) { throw invalid(HttpStatus.BAD_REQUEST,"EMBEDDING_INPUT_LIMIT_EXCEEDED"); }
        String hash=digest(List.of(r.providerId(),r.modelId(),r.providerFingerprint(),r.spaceFingerprint(),r.dimensions(),r.inputs()));
        var price=prices.findEffective(model.id(),time.now()).filter(p->p.tenantId().equals(r.tenantId())).orElse(null);
        var now=time.now(); EmbeddingCallRepository.Decision decision;
        try { decision=calls.claim(new EmbeddingCall(ids.nextId(),r.tenantId(),r.actorId(),r.operationKey(),hash,provider.id(),r.modelId(),
                r.dimensions(),price==null?null:price.id(),price==null?null:price.inputMicrosPerMillionTokens(),EmbeddingCall.State.PREPARED,
                null,null,null,null,now.plusSeconds(120),now)); }
        catch(IllegalStateException e) { throw invalid(HttpStatus.CONFLICT,"EMBEDDING_IDEMPOTENCY_CONFLICT"); }
        var call=decision.call();
        if(!decision.created()) return result(call,r.inputs().size());
        try {
            budgets.reserve(new InferenceBudgetRepository.ReserveRequest(ids.nextId(),call.tenantId(),null,call.id(),call.providerId(),call.modelId(),
                    call.priceId(),call.inputRate(),call.priceId()==null?null:0L,estimated,cost(estimated,call.inputRate()),call.id()));
        } catch(InferenceBudgetLimitException e) {
            calls.finish(call.id(),EmbeddingCall.State.PREPARED,EmbeddingCall.State.REJECTED,null,null,null,e.code());
            return result(calls.find(call.id()).orElseThrow(),r.inputs().size());
        }
        if(!calls.dispatch(call.id())) return result(calls.find(call.id()).orElseThrow(),r.inputs().size());
        try {
            var outcome=executor.execute(provider,r.modelId(),r.dimensions(),r.inputs());
            if(outcome.status()==EmbeddingBatchExecutor.Status.SUCCEEDED) {
                EmbeddingBounds.vectors(outcome.vectors(),r.inputs().size(),r.dimensions());
                if(outcome.inputTokens()!=null && outcome.inputTokens()<0) throw new IllegalArgumentException("Invalid usage");
                String response=cipher.encrypt(json.writeValueAsString(outcome.vectors()));
                calls.finish(call.id(),EmbeddingCall.State.DISPATCHED,EmbeddingCall.State.SUCCEEDED,response,outcome.inputTokens(),
                        outcome.inputTokens()==null?null:cost(outcome.inputTokens(),call.inputRate()),outcome.safeCode());
            } else calls.finish(call.id(),EmbeddingCall.State.DISPATCHED,
                    outcome.status()==EmbeddingBatchExecutor.Status.REJECTED?EmbeddingCall.State.REJECTED:EmbeddingCall.State.UNKNOWN,
                    null,null,null,outcome.safeCode());
        } catch(Exception e) {
            calls.finish(call.id(),EmbeddingCall.State.DISPATCHED,EmbeddingCall.State.UNKNOWN,null,null,null,"EMBEDDING_RESULT_UNCONFIRMED");
        }
        return result(calls.find(call.id()).orElseThrow(),r.inputs().size());
    }
    private Result result(EmbeddingCall call,int count) {
        // Settlement follows durable outcome. Re-entry repairs a crash between outcome and settlement without another POST.
        budgets.findEmbeddingReservation(call.id()).ifPresent(reservation->{
            switch(call.state()) {
                case SUCCEEDED -> { if(call.inputTokens()!=null) budgets.settle(reservation.id(),call.inputTokens(),0); else budgets.markUnknown(reservation.id()); }
                case REJECTED -> budgets.release(reservation.id());
                case UNKNOWN -> budgets.markUnknown(reservation.id());
                default -> { }
            }
        });
        if(call.state()==EmbeddingCall.State.SUCCEEDED) {
            if(call.encryptedResponse()==null)return new Result(call.id(),Status.UNKNOWN,List.of(),call.inputTokens(),call.costMicros(),"EMBEDDING_OUTPUT_ERASED");
            try {
                List<List<Double>> vectors=json.readValue(cipher.decrypt(call.encryptedResponse()),new TypeReference<>(){});
                EmbeddingBounds.vectors(vectors,count,call.dimensions());
                return new Result(call.id(),Status.SUCCEEDED,vectors,call.inputTokens(),call.costMicros(),call.safeCode());
            } catch(Exception e) { return new Result(call.id(),Status.UNKNOWN,List.of(),call.inputTokens(),call.costMicros(),"EMBEDDING_CHECKPOINT_UNAVAILABLE"); }
        }
        Status state=switch(call.state()) {case REJECTED->Status.REJECTED;case UNKNOWN->Status.UNKNOWN;default->Status.IN_PROGRESS;};
        return new Result(call.id(),state,List.of(),null,null,call.safeCode());
    }
    private boolean authorized(Request r,ModelProvider provider){
        if(provider.ownerId().equals(r.actorId()))return true;
        // Only a deliberately shared active ModelPool delegates use, never an Agent/Knowledge creator identity.
        return pools!=null && pools.findPoolsByTenantId(r.tenantId()).stream()
            .filter(p->p.status()==ModelPoolStatus.ACTIVE && p.visibility()==ModelPoolVisibility.ORGANIZATION && p.ownerId().equals(provider.ownerId()))
            .anyMatch(p->pools.findMembersByPoolId(p.id()).stream().filter(ModelPoolMember::enabled)
                .anyMatch(m->m.providerId().equals(provider.id()) && providers.findModelById(m.providerModelId())
                    .filter(model->model.providerId().equals(provider.id()) && model.modelId().equals(r.modelId())).isPresent()));
    }
    @Override public boolean eraseJobCache(String tenant,String actor,String job) {
        if(tenant==null || actor==null || !UUID.fromString(job).toString().equals(job))throw new IllegalArgumentException("Invalid cache erasure scope");
        calls.eraseOutputs(tenant,actor,job+":embedding:");return true;
    }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Result reconcile(Request r) {
        validateIdentity(r);EmbeddingBounds.validate(r.inputs(),r.dimensions(),8192);
        String hash=digest(List.of(r.providerId(),r.modelId(),r.providerFingerprint(),r.spaceFingerprint(),r.dimensions(),r.inputs()));
        var now=time.now();
        // Creating/sealing the original logical key prevents a paused old caller from dispatching it later.
        var decision=calls.claim(new EmbeddingCall(ids.nextId(),r.tenantId(),r.actorId(),r.operationKey(),hash,r.providerId(),r.modelId(),r.dimensions(),
                null,null,EmbeddingCall.State.PREPARED,null,null,null,null,now.plusSeconds(120),now));
        var call=decision.call();
        if(call.state()==EmbeddingCall.State.PREPARED)
            calls.finish(call.id(),EmbeddingCall.State.PREPARED,EmbeddingCall.State.REJECTED,null,null,null,"EMBEDDING_DISPATCH_CANCELLED");
        return result(calls.find(call.id()).orElseThrow(),r.inputs().size());
    }
    private void validateIdentity(Request r) {
        if(r==null || r.tenantId()==null || r.actorId()==null || r.providerId()==null || r.modelId()==null
                || r.operationKey()==null || !r.operationKey().matches("[A-Za-z0-9_.:-]{1,160}")
                || r.providerFingerprint()==null || !r.providerFingerprint().matches("[a-f0-9]{64}")
                || r.spaceFingerprint()==null || !r.spaceFingerprint().matches("[a-f0-9]{64}"))
            throw invalid(HttpStatus.BAD_REQUEST,"EMBEDDING_INPUT_INVALID");
    }
    private String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value))); }
        catch(Exception e) { throw new IllegalStateException("Embedding request hash failed"); }
    }
    private static Long cost(long tokens,Long rate) { return rate==null?null:Math.addExact(Math.multiplyExact(tokens,rate),999999)/1000000; }
    private static BusinessException invalid(HttpStatus status,String code) { return new BusinessException("Embedding request cannot proceed",status,code); }
}
