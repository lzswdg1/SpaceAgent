package com.spaceagent.platform.artifact.infrastructure;

import io.minio.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.util.*;

@Component
@ConditionalOnProperty(prefix="platform.artifact-object",name="mode",havingValue="s3")
class MinioArtifactObjectS3Client implements ArtifactObjectS3Client {
    private final MinioClient client;private final String bucket;
    MinioArtifactObjectS3Client(ArtifactObjectStorageProperties p){if(blank(p.getEndpoint())||blank(p.getAccessKey())||blank(p.getSecretKey())||p.getBucket()==null||!p.getBucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))throw new IllegalStateException("S3-compatible Artifact storage configuration is invalid");client=MinioClient.builder().endpoint(p.getEndpoint()).credentials(p.getAccessKey(),p.getSecretKey()).build();bucket=p.getBucket();}
    public void put(String key,byte[] bytes,String media){call(()->{client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(new ByteArrayInputStream(bytes),(long)bytes.length,-1L).contentType(media).build());return null;});}
    public Optional<ObjectInfo> stat(String key){try{var value=client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());return Optional.of(new ObjectInfo(key,value.size()));}catch(io.minio.errors.ErrorResponseException e){if(e.errorResponse()!=null&&"NoSuchKey".equals(e.errorResponse().code()))return Optional.empty();throw new IllegalStateException("S3 stat failed",e);}catch(Exception e){throw new IllegalStateException("S3 stat failed",e);}}
    public byte[] read(String key,long offset,int maximum){return call(()->{try(var input=client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).offset(offset).length((long)maximum).build())){return input.readNBytes(maximum);}});}
    public List<ObjectInfo> list(String prefix){return call(()->{List<ObjectInfo> values=new ArrayList<>();for(var result:client.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build())){var item=result.get();values.add(new ObjectInfo(item.objectName(),item.size()));}values.sort(Comparator.comparing(ObjectInfo::key));return List.copyOf(values);});}
    public void compose(String target,List<String> sources){call(()->{client.composeObject(ComposeObjectArgs.builder().bucket(bucket).object(target).sources(sources.stream().map(key->SourceObject.builder().bucket(bucket).object(key).build()).toList()).build());return null;});}
    public void delete(String key){call(()->{client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());return null;});}
    public String presignGet(String key,int ttl){return call(()->client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Http.Method.GET).bucket(bucket).object(key).expiry(ttl).build()));}
    private static boolean blank(String value){return value==null||value.isBlank();}private static <T>T call(Throwing<T> action){try{return action.run();}catch(Exception e){throw new IllegalStateException("S3-compatible Artifact operation failed",e);}}private interface Throwing<T>{T run()throws Exception;}
}
