package com.spaceagent.platform.knowledge.domain;
import java.time.Instant;import java.util.*;
public interface KnowledgeUrlRepository {
 Instant currentTime();
 void insertJob(KnowledgeUrlJob job);
 Optional<KnowledgeUrlJob> findJob(String tenantId,String ownerId,String jobId);
 Optional<KnowledgeUrlJob> findJob(String tenantId,String ownerId,String documentId,String normalizedUrl);
 List<KnowledgeUrlJob> findJobsByDocument(String tenantId,String ownerId,String documentId,int offset,int limit);
 long countJobsByDocument(String tenantId,String ownerId,String documentId);
 Optional<KnowledgeUrlJob> updateJob(KnowledgeUrlJob job,long expectedRevision);
 Optional<KnowledgeUrlEvidence.RefreshLease> claimNext(String workerId,String claimToken,int leaseSeconds);
 boolean renewLease(String jobId,String claimToken,long fencingToken,long expectedRevision,int leaseSeconds);
 boolean markLeaseUnknown(String jobId,String claimToken,long fencingToken,long expectedRevision);
 boolean releaseLease(String jobId,String claimToken,long fencingToken,long expectedRevision,Instant nextRefreshAt);
 void appendObservation(KnowledgeUrlEvidence.Observation observation);
 List<KnowledgeUrlEvidence.Observation> observations(String tenantId,String jobId,int limit);
 void insertContentVersion(KnowledgeUrlEvidence.ContentVersion version);
 List<KnowledgeUrlEvidence.ContentVersion> contentVersions(String tenantId,String jobId);
 Optional<KnowledgeUrlEvidence.ContentVersion> activateContentVersion(
         String tenantId,String jobId,String versionId,String claimToken,
         long fencingToken,long expectedLeaseRevision,Instant at);
}
