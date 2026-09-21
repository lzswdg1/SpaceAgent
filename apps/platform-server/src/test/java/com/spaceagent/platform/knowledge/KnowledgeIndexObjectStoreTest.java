package com.spaceagent.platform.knowledge;

import com.spaceagent.platform.knowledge.infrastructure.FileSystemKnowledgeIndexObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeIndexObjectStoreTest {
    @TempDir Path directory;
    @Test void objectsAreContentAddressedReopenableAndRejectTraversalOrCorruption() throws Exception {
        Path root=directory.toRealPath(); var store=new FileSystemKnowledgeIndexObjectStore(root);
        byte[] bytes="vector-output".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=FileSystemKnowledgeIndexObjectStore.hash(bytes);
        String ref=store.put("job",hash,bytes);assertThat(store.put("job",hash,bytes)).isEqualTo(ref);
        assertThat(new FileSystemKnowledgeIndexObjectStore(root).read(ref,hash)).isEqualTo(bytes);
        assertThatThrownBy(()->store.read("../../secret",hash)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(root.resolve(ref),"corrupted");
        assertThatThrownBy(()->store.read(ref,hash)).isInstanceOf(IllegalStateException.class);
    }
    @Test void symbolicLinksCannotRedirectManagedReadsOrWrites() throws Exception {
        Path root=directory.toRealPath(); var store=new FileSystemKnowledgeIndexObjectStore(root.resolve("managed"));
        Path other=Files.createDirectory(root.resolve("outside"));Files.createDirectories(root.resolve("managed/knowledge-index"));
        Files.createSymbolicLink(root.resolve("managed/knowledge-index/job"),other);
        byte[] bytes={1};String hash=FileSystemKnowledgeIndexObjectStore.hash(bytes);
        assertThatThrownBy(()->store.put("job",hash,bytes)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.list(other).toList()).isEmpty();
    }
}
