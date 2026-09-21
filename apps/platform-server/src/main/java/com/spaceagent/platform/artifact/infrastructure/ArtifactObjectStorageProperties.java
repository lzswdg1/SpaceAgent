package com.spaceagent.platform.artifact.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="platform.artifact-object")
public class ArtifactObjectStorageProperties {
    private String localRoot=".run/artifact-objects";
    private String endpoint;private String accessKey;private String secretKey;private String bucket="spaceagent-artifacts";
    private String encryptionReference="tenant-key:v1";
    private long maximumObjectBytes=512L*1024*1024;
    private int maximumActiveStagingSessionsPerUser=10;
    private long maximumActiveStagingBytesPerUser=1024L*1024*1024;
    private int maximumActiveStagingSessionsPerTenant=100;
    private long maximumActiveStagingBytesPerTenant=10L*1024*1024*1024;
    private long maximumStoredBytesPerTenant=50L*1024*1024*1024;
    public String getLocalRoot(){return localRoot;}public void setLocalRoot(String value){localRoot=value;}
    public String getEndpoint(){return endpoint;}public void setEndpoint(String value){endpoint=value;}public String getAccessKey(){return accessKey;}public void setAccessKey(String value){accessKey=value;}public String getSecretKey(){return secretKey;}public void setSecretKey(String value){secretKey=value;}public String getBucket(){return bucket;}public void setBucket(String value){bucket=value;}
    public String getEncryptionReference(){return encryptionReference;}public void setEncryptionReference(String value){encryptionReference=value;}
    public long getMaximumObjectBytes(){return maximumObjectBytes;}public void setMaximumObjectBytes(long value){maximumObjectBytes=value;}
    public int getMaximumActiveStagingSessionsPerUser(){return maximumActiveStagingSessionsPerUser;}public void setMaximumActiveStagingSessionsPerUser(int value){maximumActiveStagingSessionsPerUser=value;}
    public long getMaximumActiveStagingBytesPerUser(){return maximumActiveStagingBytesPerUser;}public void setMaximumActiveStagingBytesPerUser(long value){maximumActiveStagingBytesPerUser=value;}
    public int getMaximumActiveStagingSessionsPerTenant(){return maximumActiveStagingSessionsPerTenant;}public void setMaximumActiveStagingSessionsPerTenant(int value){maximumActiveStagingSessionsPerTenant=value;}
    public long getMaximumActiveStagingBytesPerTenant(){return maximumActiveStagingBytesPerTenant;}public void setMaximumActiveStagingBytesPerTenant(long value){maximumActiveStagingBytesPerTenant=value;}
    public long getMaximumStoredBytesPerTenant(){return maximumStoredBytesPerTenant;}public void setMaximumStoredBytesPerTenant(long value){maximumStoredBytesPerTenant=value;}
}
