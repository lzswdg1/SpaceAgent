package com.spaceagent.platform.knowledge;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class KnowledgeObjectSnapshotScriptTest {
    @TempDir Path directory;
    @Test void snapshotRoundTripRejectsOverwriteAndInventoryVerifiesHashes()throws Exception{
        Path repo=Path.of("").toAbsolutePath();while(!Files.exists(repo.resolve("scripts/knowledge-object-snapshot.sh")))repo=repo.getParent();
        byte[] data="fixture bytes".getBytes(StandardCharsets.UTF_8);String hash=com.spaceagent.platform.knowledge.application.KnowledgeIndexBuildCoordinator.hash(data);
        Path root=directory.resolve("objects");Files.createDirectories(root.resolve("knowledge-index/owner"));Files.write(root.resolve("knowledge-index/owner/"+hash),data);
        Path archive=directory.resolve("snapshot.tgz"),restored=directory.resolve("restored");
        assertThat(run(repo,"knowledge-object-snapshot.sh","backup",root.toString(),archive.toString()).status()).isZero();
        assertThat(run(repo,"knowledge-object-snapshot.sh","backup",root.toString(),archive.toString()).status()).isNotZero();
        assertThat(run(repo,"knowledge-object-snapshot.sh","restore",archive.toString(),restored.toString()).status()).isZero();
        assertThat(Files.readAllBytes(restored.resolve("knowledge-index/owner/"+hash))).isEqualTo(data);
        var inventory=run(repo,"knowledge-object-inventory.sh",root.toString(),"10");assertThat(inventory.status()).isZero();assertThat(inventory.text()).contains("knowledge-index/owner/"+hash+"\towner");
        Files.writeString(root.resolve("knowledge-index/owner/"+hash),"corrupted");assertThat(run(repo,"knowledge-object-inventory.sh",root.toString(),"10").status()).isNotZero();
    }
    private static Output run(Path repo,String script,String...args)throws Exception{
        var command=new java.util.ArrayList<String>();command.add("bash");command.add(repo.resolve("scripts/"+script).toString());command.addAll(java.util.List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        return new Output(process.waitFor(),text);
    }
    record Output(int status,String text){}
}
