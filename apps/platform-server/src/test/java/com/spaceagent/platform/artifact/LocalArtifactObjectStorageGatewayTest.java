package com.spaceagent.platform.artifact;

import com.spaceagent.platform.artifact.domain.ArtifactObjectStorageGateway;
import com.spaceagent.platform.artifact.infrastructure.LocalArtifactObjectStorageGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;

class LocalArtifactObjectStorageGatewayTest {
    @TempDir Path root;
    @Test void stagesVerifiesAtomicallyPublishesAndReadsByOpaqueRef(){var gateway=new LocalArtifactObjectStorageGateway(root);String id=uuid(1);var stage=gateway.createStaging(new ArtifactObjectStorageGateway.CreateStagingCommand("tenant",id,5));assertThat(gateway.write(new ArtifactObjectStorageGateway.WriteCommand("tenant",stage.stagingReference(),0,"hello".getBytes(StandardCharsets.UTF_8),5)).byteSize()).isEqualTo(5);assertThat(gateway.verify(new ArtifactObjectStorageGateway.VerifyCommand("tenant",stage.stagingReference(),hash("hello"),5)).contentSha256()).isEqualTo(hash("hello"));var published=gateway.publish(new ArtifactObjectStorageGateway.PublishCommand("tenant",stage.stagingReference(),hash("hello"),5,"tenant-key:v1"));assertThat(published.storageReference()).startsWith("artifact-object:");assertThat(gateway.verify(new ArtifactObjectStorageGateway.VerifyCommand("tenant",stage.stagingReference(),hash("hello"),5)).byteSize()).isEqualTo(5);assertThat(new String(gateway.read(new ArtifactObjectStorageGateway.ReadQuery("tenant",published.storageReference(),0,5)).bytes(),StandardCharsets.UTF_8)).isEqualTo("hello");}
    @Test void offsetAndHashMismatchFailWithoutPublishing(){var gateway=new LocalArtifactObjectStorageGateway(root);var stage=gateway.createStaging(new ArtifactObjectStorageGateway.CreateStagingCommand("tenant",uuid(2),5));assertThatThrownBy(()->gateway.write(new ArtifactObjectStorageGateway.WriteCommand("tenant",stage.stagingReference(),1,new byte[]{1},5))).isInstanceOf(IllegalStateException.class);gateway.write(new ArtifactObjectStorageGateway.WriteCommand("tenant",stage.stagingReference(),0,"hello".getBytes(StandardCharsets.UTF_8),5));assertThatThrownBy(()->gateway.verify(new ArtifactObjectStorageGateway.VerifyCommand("tenant",stage.stagingReference(),hash("other"),5))).isInstanceOf(IllegalStateException.class);}
    @Test void equalContentHasIndependentPublicationKeys(){var gateway=new LocalArtifactObjectStorageGateway(root);String first=publish(gateway,uuid(3),"same");String second=publish(gateway,uuid(4),"same");assertThat(second).isNotEqualTo(first);}
    @Test void opaqueReferencesRejectTraversalAndHostPaths(){var gateway=new LocalArtifactObjectStorageGateway(root);assertThatThrownBy(()->gateway.read(new ArtifactObjectStorageGateway.ReadQuery("tenant","artifact-object:../etc",0,10))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->gateway.deleteStaging(new ArtifactObjectStorageGateway.DeleteStagingCommand("tenant","/tmp/value"))).isInstanceOf(IllegalArgumentException.class);}
    @Test void readsAreBoundedAndReportTruncation(){var gateway=new LocalArtifactObjectStorageGateway(root);String ref=publish(gateway,uuid(5),"abcdef");var read=gateway.read(new ArtifactObjectStorageGateway.ReadQuery("tenant",ref,2,2));assertThat(new String(read.bytes(),StandardCharsets.UTF_8)).isEqualTo("cd");assertThat(read.totalBytes()).isEqualTo(6);assertThat(read.truncated()).isTrue();}
    private String publish(LocalArtifactObjectStorageGateway gateway,String id,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);var stage=gateway.createStaging(new ArtifactObjectStorageGateway.CreateStagingCommand("tenant",id,bytes.length));gateway.write(new ArtifactObjectStorageGateway.WriteCommand("tenant",stage.stagingReference(),0,bytes,bytes.length));return gateway.publish(new ArtifactObjectStorageGateway.PublishCommand("tenant",stage.stagingReference(),hash(value),bytes.length,"tenant-key:v1")).storageReference();}
    private static String hash(String value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}private static String uuid(int value){return "00000000-0000-4000-8000-"+String.format("%012d",value);}
}
