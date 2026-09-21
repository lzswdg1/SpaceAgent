package com.spaceagent.platform.artifact.domain;

import java.time.Instant;
import java.util.Objects;

/** Artifact-owned immutable content identity and mutable retention/deletion lifecycle. */
public record ManagedArtifactObject(
        String id, String tenantId, String createdBy, String contentSha256,
        long byteSize, String mediaType, String storageReference, String encryptionReference,
        Retention retention, State state, long revision, Instant createdAt,
        Instant updatedAt, Instant deletedAt) {
    public static final long MAX_BYTES = 5L * 1024 * 1024 * 1024;
    public ManagedArtifactObject {
        require(id,"id");require(tenantId,"tenantId");require(createdBy,"createdBy");
        hash(contentSha256);require(mediaType,"mediaType");opaque(storageReference,"artifact-object:");
        opaque(encryptionReference,"tenant-key:");Objects.requireNonNull(retention);Objects.requireNonNull(state);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
        if(byteSize<0||byteSize>MAX_BYTES||mediaType.length()>160||revision<=0
                ||(state==State.DELETED)!=(deletedAt!=null))throw new IllegalArgumentException("Managed Artifact object is invalid");
    }
    public ManagedArtifactObject requestDeletion(long activeReferences,boolean legalHold,Instant at){
        if(state!=State.READY||activeReferences!=0||legalHold||retention.retainUntil().isAfter(at))
            throw new IllegalStateException("Managed Artifact object is not deletable");
        return copy(State.DELETE_PENDING,at,null);
    }
    public ManagedArtifactObject blockDeletion(Instant at){if(state!=State.DELETE_PENDING)throw new IllegalStateException("Deletion is not pending");return copy(State.BLOCKED,at,null);}
    public ManagedArtifactObject retryDeletion(Instant at){if(state!=State.BLOCKED)throw new IllegalStateException("Deletion is not blocked");return copy(State.DELETE_PENDING,at,null);}
    public ManagedArtifactObject deleted(Instant at){if(state!=State.DELETE_PENDING)throw new IllegalStateException("Deletion is not pending");return copy(State.DELETED,at,at);}
    private ManagedArtifactObject copy(State next,Instant at,Instant deleted){return new ManagedArtifactObject(id,tenantId,createdBy,contentSha256,byteSize,mediaType,storageReference,encryptionReference,retention,next,revision+1,createdAt,at,deleted);}
    public enum State{READY,DELETE_PENDING,BLOCKED,DELETED}
    public record Retention(Instant retainUntil,boolean deleteWhenUnreferenced){public Retention{Objects.requireNonNull(retainUntil);}}
    static void hash(String value){if(value==null||!value.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("SHA-256 is invalid");}
    static void opaque(String value,String prefix){if(value==null||!value.matches(java.util.regex.Pattern.quote(prefix)+"[A-Za-z0-9_.:-]{1,300}"))throw new IllegalArgumentException("Opaque reference is invalid");}
    static void require(String value,String field){if(value==null||value.isBlank()||value.length()>200)throw new IllegalArgumentException(field+" is invalid");}
}
