package com.spaceagent.platform.artifact.domain;

/** Byte transport port. It owns no Artifact lifecycle, tenant authorization, retention or legal hold. */
public interface ArtifactObjectStorageGateway {
    StagingHandle createStaging(CreateStagingCommand command);
    WriteResult write(WriteCommand command);
    Verification verify(VerifyCommand command);
    PublishResult publish(PublishCommand command);
    ReadResult read(ReadQuery query);
    void deleteStaging(DeleteStagingCommand command);
    void deleteObject(DeleteObjectCommand command);
    default DownloadCapability presignDownload(PresignDownloadQuery query){throw new UnsupportedOperationException("Presigned download is unavailable");}

    record CreateStagingCommand(String tenantId,String stagingId,long expectedBytes){}
    record WriteCommand(String tenantId,String stagingReference,long expectedOffset,byte[] bytes,long maximumBytes){public WriteCommand{bytes=bytes==null?new byte[0]:bytes.clone();}}
    record VerifyCommand(String tenantId,String stagingReference,String expectedSha256,long expectedBytes){}
    record PublishCommand(String tenantId,String stagingReference,String contentSha256,long byteSize,String encryptionReference){}
    record ReadQuery(String tenantId,String storageReference,long offset,int maximumBytes){}
    record DeleteStagingCommand(String tenantId,String stagingReference){}
    record DeleteObjectCommand(String tenantId,String storageReference){}
    record PresignDownloadQuery(String tenantId,String storageReference,int ttlSeconds){}
    record StagingHandle(String stagingReference){}
    record WriteResult(long byteSize){}
    record Verification(String contentSha256,long byteSize){}
    record PublishResult(String storageReference,boolean reusedExisting){}
    record ReadResult(byte[] bytes,long totalBytes,boolean truncated){public ReadResult{bytes=bytes==null?new byte[0]:bytes.clone();}}
    record DownloadCapability(String url,java.time.Instant expiresAt){}
}
