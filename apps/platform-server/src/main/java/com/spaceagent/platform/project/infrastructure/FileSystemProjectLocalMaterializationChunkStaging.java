package com.spaceagent.platform.project.infrastructure;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunkStaging;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Bounded server-managed staging root; client metadata never becomes a filesystem path. */
@Component
public class FileSystemProjectLocalMaterializationChunkStaging
        implements ProjectLocalMaterializationChunkStaging {
    private final Path managedRoot;
    private final Path stagingRoot;

    public FileSystemProjectLocalMaterializationChunkStaging(WorkspaceProperties properties) {
        managedRoot = Path.of(properties.getManagedRoot()).toAbsolutePath().normalize();
        stagingRoot = managedRoot.resolve("local-materialization").normalize();
        requireUnder(stagingRoot, managedRoot);
    }

    @Override
    public StoredChunk stage(
            String sessionId, String stagingKey, long contentLength, String contentSha256, InputStream bytes) {
        validateInput(sessionId, stagingKey, contentLength, contentSha256, bytes);
        Path target = target(sessionId, stagingKey);
        try {
            if (Files.exists(target)) {
                verify(target, contentLength, contentSha256);
                return new StoredChunk(stagingKey, contentLength, contentSha256);
            }
            Files.createDirectories(target.getParent());
            Path temporary = target.getParent().resolve(stagingKey + ".partial-" + UUID.randomUUID()).normalize();
            requireUnder(temporary, stagingRoot);
            try {
                copyAndVerify(temporary, bytes, contentLength, contentSha256);
                moveNew(temporary, target, contentLength, contentSha256);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new StoredChunk(stagingKey, contentLength, contentSha256);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to stage local materialization chunk", error);
        }
    }

    @Override
    public InputStream open(String sessionId, String stagingKey) {
        Path target = target(sessionId, stagingKey);
        try {
            return Files.newInputStream(target, StandardOpenOption.READ);
        } catch (IOException error) {
            throw new IllegalStateException("Staged local materialization chunk is unavailable", error);
        }
    }

    @Override public void cleanupSession(String sessionId) {
        Path session=target(sessionId,"a".repeat(64)).getParent();
        try{deleteTree(session);}catch(IOException e){throw new IllegalStateException("Unable to clean materialization staging",e);}
    }

    private static void deleteTree(Path path)throws IOException{if(!Files.exists(path))return;Files.walkFileTree(path,new java.nio.file.SimpleFileVisitor<>(){public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{Files.delete(file);return java.nio.file.FileVisitResult.CONTINUE;}public java.nio.file.FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return java.nio.file.FileVisitResult.CONTINUE;}});}

    private Path target(String sessionId, String stagingKey) {
        requireUuid(sessionId);
        if (stagingKey == null || !stagingKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("stagingKey is invalid");
        }
        Path session = stagingRoot.resolve(sessionId).normalize();
        Path target = session.resolve(stagingKey).normalize();
        requireUnder(session, stagingRoot);
        requireUnder(target, session);
        return target;
    }

    private static void validateInput(
            String sessionId, String stagingKey, long contentLength, String contentSha256, InputStream bytes) {
        requireUuid(sessionId);
        if (stagingKey == null || !stagingKey.matches("[0-9a-f]{64}")
                || contentLength <= 0 || contentLength > ProjectLocalMaterializationChunk.MAX_CONTENT_LENGTH
                || contentSha256 == null || !contentSha256.matches("sha256:[0-9a-f]{64}") || bytes == null) {
            throw new IllegalArgumentException("Chunk staging input is invalid");
        }
    }

    private static void copyAndVerify(Path temporary, InputStream bytes, long expectedLength, String expectedHash)
            throws IOException {
        MessageDigest digest = digest();
        long copied = 0;
        try (bytes; var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = bytes.read(buffer)) >= 0; ) {
                copied += read;
                if (copied > expectedLength) {
                    throw new IllegalArgumentException("Chunk body exceeds declared length");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (copied != expectedLength || !expectedHash.equals("sha256:" + HexFormat.of().formatHex(digest.digest()))) {
            throw new IllegalArgumentException("Chunk body does not match declared digest");
        }
    }

    private static void moveNew(Path temporary, Path target, long length, String hash) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            verify(target, length, hash);
        }
    }

    private static void verify(Path target, long expectedLength, String expectedHash) throws IOException {
        if (Files.size(target) != expectedLength) {
            throw new IllegalStateException("Existing staged chunk length differs");
        }
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(target)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        if (!expectedHash.equals("sha256:" + HexFormat.of().formatHex(digest.digest()))) {
            throw new IllegalStateException("Existing staged chunk digest differs");
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("sessionId is invalid");
        }
    }

    private static void requireUnder(Path path, Path base) {
        if (!path.startsWith(base.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Chunk staging path escaped managed root");
        }
    }
}
