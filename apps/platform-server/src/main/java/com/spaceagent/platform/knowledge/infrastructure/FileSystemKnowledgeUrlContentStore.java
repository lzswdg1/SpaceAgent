package com.spaceagent.platform.knowledge.infrastructure;

import com.spaceagent.platform.knowledge.domain.KnowledgeUrlContentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class FileSystemKnowledgeUrlContentStore implements KnowledgeUrlContentStore {
    private final Path root;

    @Autowired
    public FileSystemKnowledgeUrlContentStore(
            @Value("${platform.knowledge.url-content-root:data/knowledge-url}") String root) {
        this(Path.of(root));
    }

    public FileSystemKnowledgeUrlContentStore(Path root) {
        this.root=root.toAbsolutePath().normalize();
    }

    @Override public String put(String job,String hash,byte[] bytes){
        validate(job,hash);if(bytes==null||bytes.length>10_000_000)throw new IllegalArgumentException("URL content size invalid");
        String actual=sha256(bytes);if(!hash.equals(actual))throw new IllegalArgumentException("URL content hash mismatch");
        String ref="knowledge-url/"+job+"/"+hash.substring("sha256:".length())+".bin";Path target=resolve(ref);
        try{Files.createDirectories(target.getParent());if(Files.exists(target)){if(!sha256(Files.readAllBytes(target)).equals(hash))throw new IllegalStateException("Managed URL content collision");return ref;}Path temp=target.resolveSibling(target.getFileName()+"."+UUID.randomUUID()+".partial");Files.write(temp,bytes,StandardOpenOption.CREATE_NEW);try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target);}return ref;}catch(Exception e){if(e instanceof RuntimeException r)throw r;throw new IllegalStateException("Unable to publish managed URL content");}
    }

    @Override public byte[] read(String ref,int maximum){if(maximum<1||maximum>10_000_000)throw new IllegalArgumentException("URL content read bound invalid");Path path=resolve(ref);try{long size=Files.size(path);if(size>maximum)throw new IllegalStateException("Managed URL content exceeds bound");return Files.readAllBytes(path);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Managed URL content unavailable");}}
    private Path resolve(String ref){if(ref==null||!ref.matches("knowledge-url/[0-9a-fA-F-]{36}/[0-9a-f]{64}\\.bin"))throw new IllegalArgumentException("URL object reference invalid");Path path=root.resolve(ref).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("URL object reference escaped root");return path;}
    private static void validate(String job,String hash){try{UUID.fromString(job);}catch(Exception e){throw new IllegalArgumentException("URL job id invalid");}if(hash==null||!hash.matches("sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("URL content hash invalid");}
    private static String sha256(byte[] bytes){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}}
}
