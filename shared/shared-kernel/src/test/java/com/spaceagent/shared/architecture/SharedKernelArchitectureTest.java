package com.spaceagent.shared.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedKernelArchitectureTest {

    private static final Path SHARED_MAIN_JAVA = locateSharedMainJava();

    private static final Set<String> FORBIDDEN_BUSINESS_TYPE_NAMES = Set.of(
            "ChatTurnCompletedEvent",
            "MemoryClearRequestedEvent",
            "SkillClearRequestedEvent",
            "UserServiceClient",
            "SkillServiceClient",
            "UserInfo",
            "UserProfileInfo",
            "SkillInfo"
    );

    private static final Set<String> FORBIDDEN_BUSINESS_IMPORTS = Set.of(
            "com.spaceagent.identity.",
            "com.spaceagent.agent.",
            "com.spaceagent.chat.",
            "com.spaceagent.gateway.",
            "com.spaceagent.knowledge.",
            "com.spaceagent.project.",
            "com.spaceagent.conversation.",
            "com.spaceagent.memory.",
            "com.spaceagent.context.",
            "com.spaceagent.runtime.",
            "com.spaceagent.inference.",
            "com.spaceagent.tooling.",
            "com.spaceagent.automation.",
            "com.spaceagent.integration.",
            "com.spaceagent.governance.",
            "com.spaceagent.observability."
    );

    @Test
    void sharedKernelDoesNotDefineBusinessShapedTypes() throws IOException {
        List<String> violations = javaFiles(SHARED_MAIN_JAVA)
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(fileName -> FORBIDDEN_BUSINESS_TYPE_NAMES.stream()
                        .anyMatch(typeName -> fileName.equals(typeName + ".java")))
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "shared-kernel must not own business-shaped types; move these types to their owning module: "
                        + violations);
    }

    @Test
    void sharedKernelDoesNotDependOnBusinessModules() throws IOException {
        List<String> violations = javaFiles(SHARED_MAIN_JAVA)
                .flatMap(SharedKernelArchitectureTest::imports)
                .filter(SharedKernelArchitectureTest::isForbiddenBusinessImport)
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "shared-kernel must not depend on business modules; found imports: " + violations);
    }

    @Test
    void sharedKernelContainsNoServiceClientsEventsOrBusinessPersistence() throws IOException {
        List<String> violations = javaFiles(SHARED_MAIN_JAVA)
                .filter(SharedKernelArchitectureTest::isForbiddenSharedContract)
                .map(path -> SHARED_MAIN_JAVA.relativize(path).toString())
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(),
                "shared-kernel must not contain service clients, event buses, or business persistence contracts: "
                        + violations);
    }

    private static boolean isForbiddenSharedContract(Path path) {
        String normalized = path.toString().replace('\\', '/');
        String fileName = path.getFileName().toString();
        return normalized.contains("/client/")
                || normalized.contains("/event/")
                || normalized.contains("/persistence/")
                || fileName.endsWith("Repository.java")
                || fileName.endsWith("Dao.java")
                || fileName.endsWith("Mapper.java");
    }

    private static boolean isForbiddenBusinessImport(String importLine) {
        return FORBIDDEN_BUSINESS_IMPORTS.stream().anyMatch(importLine::startsWith);
    }

    private static Stream<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()
                    .stream();
        }
    }

    private static Stream<String> imports(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .filter(line -> line.startsWith("import "))
                    .map(String::trim);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read " + file, error);
        }
    }

    private static Path locateSharedMainJava() {
        Path basedirCandidate = Path.of(System.getProperty("basedir", "."), "src/main/java");
        if (Files.isDirectory(basedirCandidate)) {
            return basedirCandidate;
        }
        Path reactorCandidate = Path.of("shared/shared-kernel/src/main/java");
        if (Files.isDirectory(reactorCandidate)) {
            return reactorCandidate;
        }
        return Path.of("src/main/java");
    }
}
