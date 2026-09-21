package com.spaceagent.platform.knowledge.domain;

import java.time.Instant;
import java.util.Objects;

/** Non-Project, non-Git document namespace owned by Knowledge. */
public record DocumentWorkspace(
        String id,
        String tenantId,
        ScopeType scopeType,
        String scopeId,
        String ownerId,
        String name,
        String objectNamespace,
        Quota quota,
        Usage usage,
        State state,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public DocumentWorkspace {
        require(id,"id");require(tenantId,"tenantId");require(scopeId,"scopeId");require(ownerId,"ownerId");
        require(name,"name");require(objectNamespace,"objectNamespace");Objects.requireNonNull(scopeType);
        Objects.requireNonNull(quota);Objects.requireNonNull(usage);Objects.requireNonNull(state);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);
        if(name.length()>120||!objectNamespace.matches("document-workspace:[A-Za-z0-9_.:-]{1,160}")
                ||scopeType==ScopeType.USER&&!scopeId.equals(ownerId)
                ||scopeType==ScopeType.ORGANIZATION&&!scopeId.equals(tenantId)
                ||revision<=0||usage.fileCount()>quota.maximumFiles()||usage.byteCount()>quota.maximumBytes()
                ||state==State.ARCHIVED!=(archivedAt!=null))throw new IllegalArgumentException("Document Workspace is invalid");
    }

    public DocumentWorkspace reserve(long files,long bytes,Instant at){if(state!=State.ACTIVE||files<0||bytes<0)throw new IllegalStateException("Document Workspace is not writable");Usage next=new Usage(Math.addExact(usage.fileCount(),files),Math.addExact(usage.byteCount(),bytes));if(next.fileCount()>quota.maximumFiles()||next.byteCount()>quota.maximumBytes())throw new IllegalStateException("Document Workspace quota exceeded");return copy(quota,next,state,at,null);}
    public DocumentWorkspace release(long files,long bytes,Instant at){if(files<0||bytes<0||files>usage.fileCount()||bytes>usage.byteCount())throw new IllegalArgumentException("Document Workspace release is invalid");return copy(quota,new Usage(usage.fileCount()-files,usage.byteCount()-bytes),state,at,archivedAt);}
    public DocumentWorkspace changeQuota(Quota next,Instant at){if(next.maximumFiles()<usage.fileCount()||next.maximumBytes()<usage.byteCount())throw new IllegalArgumentException("Quota cannot be below usage");return copy(next,usage,state,at,archivedAt);}
    public DocumentWorkspace pause(Instant at){if(state!=State.ACTIVE)throw new IllegalStateException("Only ACTIVE Workspace can pause");return copy(quota,usage,State.PAUSED,at,null);}
    public DocumentWorkspace resume(Instant at){if(state!=State.PAUSED)throw new IllegalStateException("Only PAUSED Workspace can resume");return copy(quota,usage,State.ACTIVE,at,null);}
    public DocumentWorkspace archive(Instant at){if(state==State.ARCHIVED)return this;return copy(quota,usage,State.ARCHIVED,at,at);}
    private DocumentWorkspace copy(Quota q,Usage u,State s,Instant at,Instant archived){return new DocumentWorkspace(id,tenantId,scopeType,scopeId,ownerId,name,objectNamespace,q,u,s,revision+1,createdAt,at,archived);}
    public enum ScopeType{USER,ORGANIZATION}public enum State{ACTIVE,PAUSED,ARCHIVED}
    public record Quota(long maximumFiles,long maximumBytes){public Quota{if(maximumFiles<1||maximumFiles>10_000||maximumBytes<1_048_576||maximumBytes>1_073_741_824L)throw new IllegalArgumentException("Document Workspace quota is invalid");}}
    public record Usage(long fileCount,long byteCount){public Usage{if(fileCount<0||byteCount<0)throw new IllegalArgumentException("Document Workspace usage is invalid");}}
    private static void require(String v,String f){if(v==null||v.isBlank()||v.length()>200)throw new IllegalArgumentException(f+" is invalid");}
}
