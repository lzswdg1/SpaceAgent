package com.spaceagent.platform.project.infrastructure;

import com.spaceagent.platform.project.domain.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class FileSystemProjectLocalMaterializationSnapshotGateway
        implements ProjectLocalMaterializationSnapshotGateway {
    private final Path root;
    private final ProjectLocalMaterializationChunkStaging staging;

    public FileSystemProjectLocalMaterializationSnapshotGateway(
            WorkspaceProperties properties, ProjectLocalMaterializationChunkStaging staging) {
        this.root = Path.of(properties.getManagedRoot()).toAbsolutePath().normalize().resolve("sources").normalize();
        this.staging = staging;
    }

    @Override
    public Snapshot publish(ProjectLocalMaterializationSession session,
            ProjectLocalMaterializationManifestValidator.Manifest manifest,
            List<ProjectLocalMaterializationChunk> chunks) {
        ProjectLocalMaterializationManifestValidator.validate(manifest, chunks);
        requireUuid(session.id());
        Path target = root.resolve(session.id()).normalize();
        requireUnder(target, root);
        try {
            if (Files.exists(target)) {
                verifyTree(target, manifest);
                return new Snapshot("sources/" + session.id(), manifest.manifestSha256());
            }
            Files.createDirectories(root);
            Path temporary = root.resolve("." + session.id() + ".partial-" + UUID.randomUUID()).normalize();
            requireUnder(temporary, root);
            try {
                Files.createDirectory(temporary);
                Map<String,List<ProjectLocalMaterializationChunk>> byPath = new HashMap<>();
                for (var chunk : chunks) byPath.computeIfAbsent(chunk.relativePath(), ignored -> new ArrayList<>()).add(chunk);
                for (var entry : manifest.entries()) {
                    Path file = temporary.resolve(entry.relativePath()).normalize();
                    requireUnder(file, temporary);
                    Files.createDirectories(file.getParent());
                    MessageDigest digest = digest();
                    long written = 0;
                    try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                        for (var chunk : byPath.getOrDefault(entry.relativePath(), List.of()).stream()
                                .sorted(Comparator.comparingLong(ProjectLocalMaterializationChunk::offset)).toList()) {
                            try (InputStream input = staging.open(session.id(), chunk.stagingKey())) {
                                byte[] buffer = new byte[8192];
                                for (int read; (read = input.read(buffer)) >= 0; ) {
                                    written += read; digest.update(buffer, 0, read); output.write(buffer, 0, read);
                                }
                            }
                        }
                    }
                    if (written != entry.contentLength() || !entry.contentSha256().equals(
                            "sha256:" + HexFormat.of().formatHex(digest.digest()))) {
                        throw new IllegalStateException("Published snapshot file hash mismatch");
                    }
                }
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target); }
            } finally {
                deleteTree(temporary);
            }
            return new Snapshot("sources/" + session.id(), manifest.manifestSha256());
        } catch (IOException error) {
            throw new IllegalStateException("Unable to publish managed Source snapshot", error);
        }
    }

    @Override public void deleteSnapshot(String snapshotRef) {
        if(snapshotRef==null||!snapshotRef.matches("sources/[0-9a-f-]{36}"))throw new IllegalArgumentException("snapshotRef is invalid");
        Path target=root.getParent().resolve(snapshotRef).normalize();requireUnder(target,root);
        try{deleteTree(target);}catch(IOException e){throw new IllegalStateException("Unable to delete managed Source snapshot",e);}
    }

    private static void verifyTree(Path root, ProjectLocalMaterializationManifestValidator.Manifest manifest)
            throws IOException {
        long count;
        try (var files = Files.walk(root)) { count = files.filter(Files::isRegularFile).count(); }
        if (count != manifest.fileCount()) throw new IllegalStateException("Existing snapshot file count differs");
        for (var entry : manifest.entries()) {
            Path file = root.resolve(entry.relativePath()).normalize(); requireUnder(file, root);
            if (!Files.isRegularFile(file) || Files.size(file) != entry.contentLength())
                throw new IllegalStateException("Existing snapshot file differs");
            MessageDigest digest = digest();
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; for (int read; (read=input.read(buffer))>=0;) digest.update(buffer,0,read);
            }
            if (!entry.contentSha256().equals("sha256:"+HexFormat.of().formatHex(digest.digest())))
                throw new IllegalStateException("Existing snapshot digest differs");
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE; }
        });
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static void requireUuid(String value){try{UUID.fromString(value);}catch(Exception e){throw new IllegalArgumentException("sessionId is invalid");}}
    private static void requireUnder(Path path,Path base){if(!path.startsWith(base.toAbsolutePath().normalize()))throw new IllegalArgumentException("snapshot path escaped managed root");}
}
