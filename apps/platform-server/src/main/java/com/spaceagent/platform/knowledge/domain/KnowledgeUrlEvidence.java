package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.Objects;

public final class KnowledgeUrlEvidence {
    private KnowledgeUrlEvidence() {}
    public enum ObservationOutcome{FETCHED,NOT_MODIFIED,REJECTED,FAILED,UNKNOWN}
    public enum ContentState{STAGED,ACTIVE,SUPERSEDED,REJECTED}

    public record RefreshLease(KnowledgeUrlJob job,String claimToken,String claimOwner,
            Instant leaseUntil,long fencingToken,long revision,Instant databaseNow){
        public RefreshLease{Objects.requireNonNull(job);require(claimToken,"claimToken");require(claimOwner,"claimOwner");Objects.requireNonNull(leaseUntil);Objects.requireNonNull(databaseNow);if(fencingToken<=0||revision!=job.revision())throw new IllegalArgumentException("URL refresh lease is invalid");}
    }

    public record Observation(String id,String urlJobId,String tenantId,String requestUrlSha256,
            String finalUrl,String finalUrlSha256,int httpStatus,String etag,String lastModified,
            String contentSha256,ObservationOutcome outcome,String safeErrorCode,Instant observedAt){
        public Observation{require(id,"id");require(urlJobId,"urlJobId");require(tenantId,"tenantId");hash(requestUrlSha256);hash(finalUrlSha256);Objects.requireNonNull(outcome);Objects.requireNonNull(observedAt);if(finalUrl!=null&&!KnowledgeUrlJob.normalize(finalUrl).equals(finalUrl)||httpStatus<0||httpStatus>599||contentSha256!=null&&!contentSha256.matches("sha256:[0-9a-f]{64}")||safeErrorCode!=null&&!safeErrorCode.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IllegalArgumentException("URL observation is invalid");if(outcome==ObservationOutcome.NOT_MODIFIED&&httpStatus!=304||outcome==ObservationOutcome.FETCHED&&(httpStatus<200||httpStatus>=300||contentSha256==null)||outcome==ObservationOutcome.UNKNOWN&&safeErrorCode==null)throw new IllegalArgumentException("URL observation outcome is invalid");}
    }

    public record ContentVersion(String id,String urlJobId,String knowledgeDocumentId,String tenantId,
            int version,String contentSha256,String objectReference,String mediaType,String charset,
            long byteSize,ContentState state,String sourceObservationId,Instant createdAt,Instant activatedAt){
        public ContentVersion{require(id,"id");require(urlJobId,"urlJobId");require(knowledgeDocumentId,"knowledgeDocumentId");require(tenantId,"tenantId");hash(contentSha256);require(objectReference,"objectReference");require(mediaType,"mediaType");require(charset,"charset");Objects.requireNonNull(state);require(sourceObservationId,"sourceObservationId");Objects.requireNonNull(createdAt);boolean wasActive=state==ContentState.ACTIVE||state==ContentState.SUPERSEDED;if(version<=0||byteSize<0||byteSize>10_000_000||objectReference.startsWith("/")||objectReference.matches("^[A-Za-z]:.*")||wasActive!=(activatedAt!=null))throw new IllegalArgumentException("URL content version is invalid");}
    }
    private static void hash(String v){if(v==null||!v.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("SHA-256 evidence is invalid");}
    private static void require(String v,String f){if(v==null||v.isBlank()||v.length()>500)throw new IllegalArgumentException(f+" is invalid");}
}
