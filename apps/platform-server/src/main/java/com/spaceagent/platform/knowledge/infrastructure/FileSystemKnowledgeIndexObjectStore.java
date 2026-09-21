package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.KnowledgeIndexObjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class FileSystemKnowledgeIndexObjectStore implements KnowledgeIndexObjectStore {
    private final Path root;
    private com.spaceagent.platform.knowledge.domain.KnowledgeObjectInventory inventory;
    @Autowired(required=false) public void inventory(com.spaceagent.platform.knowledge.domain.KnowledgeObjectInventory inventory) {this.inventory=inventory;}
    @Autowired public FileSystemKnowledgeIndexObjectStore(@Value("${platform.knowledge.index-content-root:data/knowledge-index}") String root) { this(Path.of(root)); }
    public FileSystemKnowledgeIndexObjectStore(Path root) { this.root=root.toAbsolutePath().normalize(); }
    public String put(String job,String hash,byte[] bytes) {
        if(job==null || !job.matches("[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,35}"))throw new IllegalArgumentException("Invalid index object owner");
        String ref="knowledge-index/"+job+"/"+hash;
        Path target=resolve(ref,hash);
        if(bytes==null || bytes.length>8_000_000 || !hash.equals(hash(bytes))) throw new IllegalArgumentException("Invalid index object");
        if(inventory!=null) inventory.record(ref,job);
        Path temporary=null;
        try {
            parents(target);
            Files.createDirectories(target.getParent(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            parents(target);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) { read(ref,hash); return ref; }
            if(Files.getFileStore(target.getParent()).getUsableSpace()<16_777_216L+bytes.length)
                throw new IllegalStateException("Managed object storage capacity exhausted");
            temporary=Files.createTempFile(target.getParent(),"index-",".partial",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary,bytes);
            try(var file=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)) { file.force(true); }
            try { Files.createLink(target,temporary); } catch(FileAlreadyExistsException e) { read(ref,hash); }
            return ref;
        } catch(Exception e) { throw new IllegalStateException("Managed index object could not be persisted"); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(java.io.IOException ignored) { /* Orphan sweep owns leftover temporary files. */ } }
    }
    public byte[] read(String ref,String expected) {
        Path target=resolve(ref,expected);
        try {
            parents(target);
            try(var channel=Files.newByteChannel(target,java.util.Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
                var stream=java.nio.channels.Channels.newInputStream(channel)) {
                byte[] data=stream.readNBytes(8_000_001);
                if(data.length>8_000_000 || !hash(data).equals(expected)) throw new IllegalStateException("Index object integrity check failed");
                return data;
            }
        } catch(Exception e) { throw new IllegalStateException("Managed index object is unavailable or invalid"); }
    }
    private Path resolve(String ref,String hash) {
        if(ref==null || !ref.matches("knowledge-index/[A-Za-z0-9_.:-]{1,36}/[a-f0-9]{64}") || hash==null
                || !hash.matches("[a-f0-9]{64}") || !ref.endsWith("/"+hash)) throw new IllegalArgumentException("Invalid index object reference");
        Path target=root.resolve(ref).normalize(); if(!target.startsWith(root)) throw new IllegalArgumentException("Index reference escaped root");
        return target;
    }
    @Override public void deleteReference(String reference) {
        String digest=reference==null?"":reference.substring(reference.lastIndexOf('/')+1);
        Path target=resolve(reference,digest); parents(target);
        try {if(Files.isSymbolicLink(target) || Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Unexpected object type");
            Files.deleteIfExists(target);
        } catch(java.io.IOException e){throw new IllegalStateException("Index object deletion failed");}
    }
    @Override public boolean deleteOwner(String owner,int limit) {
        if(owner==null || !owner.matches("[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,35}") || limit<1 || limit>256) throw new IllegalArgumentException("Invalid object owner cleanup");
        Path parent=root.resolve("knowledge-index").resolve(owner).normalize();
        if(!parent.getParent().equals(root.resolve("knowledge-index"))) throw new IllegalArgumentException("Invalid object cleanup path");
        parents(parent.resolve("check"));
        try {
            if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS)) return true;
            int removed=0;
            try(var files=Files.newDirectoryStream(parent)) {
                for(Path file:files) {
                    if(removed++>=limit) return false;
                    String name=file.getFileName().toString();
                    if(!name.matches("[a-f0-9]{64}|index-[A-Za-z0-9_.-]+\\.partial") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                        throw new IllegalStateException("Unexpected managed object entry");
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(parent);return true;
        } catch(java.io.IOException e){throw new IllegalStateException("Managed object cleanup failed");}
    }
    @Override public void deleteStalePartials(String owner) {
        if(owner==null || !owner.matches("[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,35}"))throw new IllegalArgumentException("Invalid object owner");
        Path parent=root.resolve("knowledge-index").resolve(owner);parents(parent.resolve("check"));
        if(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS))return;
        try(var files=Files.newDirectoryStream(parent,"index-*.partial")) {
            int count=0;for(Path file:files){if(count++>=256)break;
                if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(java.time.Instant.now().minusSeconds(86400)))Files.deleteIfExists(file);
            }
        }catch(java.io.IOException e){throw new IllegalStateException("Partial object cleanup failed");}
    }
    private void parents(Path path) {
        for(Path parent=path.getParent();parent!=null;parent=parent.getParent())
            if(Files.isSymbolicLink(parent)) throw new IllegalStateException("Index object parent is a symbolic link");
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException("Index hashing unavailable"); }
    }
}
