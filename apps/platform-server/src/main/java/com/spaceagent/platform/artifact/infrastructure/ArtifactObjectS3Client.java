package com.spaceagent.platform.artifact.infrastructure;

import java.util.List;
import java.util.Optional;

public interface ArtifactObjectS3Client {
    void put(String key,byte[] bytes,String mediaType);
    Optional<ObjectInfo> stat(String key);
    byte[] read(String key,long offset,int maximumBytes);
    List<ObjectInfo> list(String prefix);
    void compose(String target,List<String> sources);
    void delete(String key);
    String presignGet(String key,int ttlSeconds);
    record ObjectInfo(String key,long byteSize){}
}
