package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.application.RuntimeCapabilityCatalogApplicationService;
import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import com.spaceagent.platform.tooling.domain.SkillDefinition;
import com.spaceagent.platform.tooling.domain.SkillLifecycle;
import com.spaceagent.platform.tooling.domain.SkillVersion;
import com.spaceagent.platform.tooling.domain.SkillVersionStatus;
import com.spaceagent.platform.tooling.infrastructure.SandboxProperties;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemorySkillRegistryRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeCapabilityCatalogApplicationServiceTest {

    @Test
    void exposesStableDefinitionsAndValidatesArguments() {
        SandboxProperties sandbox = new SandboxProperties();
        sandbox.setMode("http");
        var catalog = new RuntimeCapabilityCatalogApplicationService(
                new ObjectMapper(), availableSearch(), sandbox);

        assertThat(catalog.catalog().tools()).hasSize(20);
        assertThat(catalog.catalog().tools()).extracting(tool -> tool.id())
                .startsWith("echo", "web_search", "http_fetch", "knowledge_search")
                .contains("mcp_call", "document_write", "document_workspace_write", "coding_run_command");
        assertThat(catalog.resolveTools(List.of("tool:echo", "file_read", "file_read")))
                .extracting(tool -> tool.id())
                .containsExactly("echo", "file_read");

        var arguments = catalog.validateArguments(
                "file_read",
                "{\"workspaceId\":\"00000000-0000-4000-8000-000000000001\","
                        + "\"path\":\"src/Main.java\",\"maxCharacters\":5000}");
        assertThat(arguments.canonicalToolId()).isEqualTo("file_read");
        assertThat(arguments.arguments()).containsEntry("path", "src/Main.java");

        assertThatThrownBy(() -> catalog.validateArguments(
                "file_read",
                "{\"workspaceId\":\"00000000-0000-4000-8000-000000000001\","
                        + "\"path\":\"../secret\"}"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_ARGUMENTS_INVALID"));
        assertThatThrownBy(() -> catalog.validateArguments(
                "echo", "{\"text\":\"hello\",\"unexpected\":true}"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void configuredAvailabilityIsAuthoritative() {
        var catalog = new RuntimeCapabilityCatalogApplicationService(
                new ObjectMapper(), unavailableSearch());

        assertThat(catalog.supportsTool("web_search")).isFalse();
        assertThat(catalog.findTool("web_search")).get()
                .extracting(tool -> tool.available()).isEqualTo(false);
        assertThatThrownBy(() -> catalog.validateArguments(
                "web_search", "{\"query\":\"SpaceAgent\"}"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TOOL_UNAVAILABLE"));

        assertThat(catalog.supportsTool("coding_run_command")).isFalse();
        assertThat(catalog.catalog().sandbox().mode()).isEqualTo("IN_PROCESS");
        assertThat(catalog.catalog().sandbox().containerized()).isFalse();
        assertThat(catalog.catalog().sandbox().codingCommandAvailable()).isFalse();
        SandboxProperties sandbox = new SandboxProperties();
        sandbox.setMode("http");
        var containerCatalog = new RuntimeCapabilityCatalogApplicationService(
                new ObjectMapper(), availableSearch(), sandbox);
        assertThat(containerCatalog.supportsTool("coding_run_command")).isTrue();
        assertThat(containerCatalog.catalog().sandbox().mode()).isEqualTo("HTTP");
        assertThat(containerCatalog.catalog().sandbox().isolation()).isEqualTo("OCI_CONTAINER");
        assertThat(containerCatalog.catalog().sandbox().containerized()).isTrue();
        assertThat(containerCatalog.catalog().sandbox().codingCommandAvailable()).isTrue();
    }

    @Test
    void requiresEnabledToolsForNewBindingsButResolvesDeprecatedPinnedVersion() {
        Instant now = Instant.parse("2026-09-07T03:00:00Z");
        var repository = new InMemorySkillRegistryRepository();
        String skillId = "skill-1";
        String versionId = "skill-version-1";
        repository.saveDefinition(new SkillDefinition(
                skillId, "tenant-1", "user-1", "Evidence", null,
                SkillLifecycle.ACTIVE, versionId, 1, now, now, null));
        SkillVersion published = new SkillVersion(
                versionId, skillId, 1, SkillVersionStatus.PUBLISHED, "a".repeat(64),
                "Inspect evidence.", List.of("echo"), "user-1", now,
                "user-1", now, null, null);
        repository.insertVersion(published);
        SandboxProperties sandbox = new SandboxProperties();
        sandbox.setMode("http");
        var catalog = new RuntimeCapabilityCatalogApplicationService(
                new ObjectMapper(), availableSearch(), sandbox, repository);

        assertThatThrownBy(() -> catalog.validateSkillBindings(
                "tenant-1", List.of(versionId), List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("AGENT_SKILL_TOOL_NOT_ENABLED"));
        catalog.validateSkillBindings("tenant-1", List.of(versionId), List.of("tool:echo"));

        repository.updateVersionLifecycle(published.deprecate("user-1", now.plusSeconds(1)));
        repository.saveDefinition(repository.findDefinition(skillId).orElseThrow()
                .archive(now.plusSeconds(1)));
        assertThat(catalog.resolvePinnedSkills(
                "tenant-1", List.of(versionId), List.of("echo")))
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.id()).isEqualTo(versionId);
                    assertThat(skill.instructions()).isEqualTo("Inspect evidence.");
                    assertThat(skill.configHash()).isEqualTo("a".repeat(64));
                });
        assertThatThrownBy(() -> catalog.validateSkillBindings(
                "tenant-1", List.of(versionId), List.of("echo")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isIn("AGENT_SKILL_NOT_PUBLISHED", "AGENT_SKILL_NOT_CURRENT"));
        assertThatThrownBy(() -> catalog.resolvePinnedSkills(
                "tenant-2", List.of(versionId), List.of("echo")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo("AGENT_SKILL_NOT_VISIBLE"));
    }

    private static WebSearchGateway availableSearch() {
        return search(true);
    }

    private static WebSearchGateway unavailableSearch() {
        return search(false);
    }

    private static WebSearchGateway search(boolean available) {
        return new WebSearchGateway() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public SearchResult search(SearchRequest request) {
                return new SearchResult(request.query(), List.of());
            }
        };
    }
}
