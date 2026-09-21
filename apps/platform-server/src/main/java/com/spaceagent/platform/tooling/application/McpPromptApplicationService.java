package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.McpBindingValidationApplicationApi;
import com.spaceagent.platform.tooling.api.McpPromptApplicationApi;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class McpPromptApplicationService implements McpPromptApplicationApi {
    private static final int MAX_ARGUMENTS = 32;
    private static final int MAX_ARGUMENT_BYTES = 4_000;
    private static final int MAX_MESSAGES = 32;
    private static final int MAX_MESSAGE_BYTES = 50_000;
    private static final int MAX_CONTEXT_BYTES = 200_000;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:/-]{1,200}");
    private static final Pattern ARGUMENT_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|authorization|bearer|password|private[ _-]?key)\\s*[:=]");

    private final McpMarketplaceRepository marketplace;
    private final McpBindingValidationApplicationApi validator;
    private final McpConnectionAuthorizationService authorization;
    private final McpRemoteToolGateway gateway;

    public McpPromptApplicationService(
            McpMarketplaceRepository marketplace,
            McpBindingValidationApplicationApi validator,
            McpConnectionAuthorizationService authorization,
            McpRemoteToolGateway gateway) {
        this.marketplace = marketplace;
        this.validator = validator;
        this.authorization = authorization;
        this.gateway = gateway;
    }

    @Override
    public List<PromptView> list(ListCommand command) {
        McpConnectionAuthorizationService.AuthorizedConnection connection = authorized(
                command.tenantId(), command.ownerUserId(), command.binding());
        return safeCatalog(gateway.listPrompts(
                connection.connection(), connection.authorization()));
    }

    @Override
    public PromptResult get(GetCommand command) {
        validName(command.promptName(), NAME, "MCP_PROMPT_NAME_INVALID");
        McpConnectionAuthorizationService.AuthorizedConnection connection = authorized(
                command.tenantId(), command.ownerUserId(), command.binding());
        PromptView prompt = safeCatalog(gateway.listPrompts(
                connection.connection(), connection.authorization())).stream()
                .filter(value -> value.name().equals(command.promptName()))
                .findFirst()
                .orElseThrow(() -> invalid("MCP_PROMPT_NOT_ADVERTISED"));
        Map<String, String> arguments = command.arguments() == null
                ? Map.of() : Map.copyOf(command.arguments());
        validateArguments(prompt, arguments);
        McpRemoteToolGateway.RemotePromptResult result = gateway.getPrompt(
                connection.connection(), connection.authorization(), prompt.name(), arguments);
        bounded(result.description(), 1_000, "MCP_PROMPT_CONTENT_INVALID");
        if (result.messages() == null || result.messages().size() > MAX_MESSAGES) {
            throw invalid("MCP_PROMPT_CONTENT_INVALID");
        }
        int total = 0;
        java.util.ArrayList<MessageView> messages = new java.util.ArrayList<>();
        for (McpRemoteToolGateway.RemotePromptMessage message : result.messages()) {
            if (!message.textContent()
                    || !("USER".equals(message.role()) || "ASSISTANT".equals(message.role()))
                    || message.text() == null
                    || SECRET.matcher(message.text()).find()) {
                throw invalid("MCP_PROMPT_CONTENT_INVALID");
            }
            int bytes = utf8(message.text());
            if (bytes > MAX_MESSAGE_BYTES || total > MAX_CONTEXT_BYTES - bytes) {
                throw invalid("MCP_PROMPT_CONTENT_INVALID");
            }
            total += bytes;
            messages.add(new MessageView(message.role(), message.text(), bytes));
        }
        return new PromptResult(result.description(), List.copyOf(messages), total);
    }

    private List<PromptView> safeCatalog(
            List<McpRemoteToolGateway.RemotePrompt> prompts) {
        if (prompts == null || prompts.size() > 100) {
            throw invalid("MCP_PROMPT_CATALOG_INVALID");
        }
        Set<String> promptNames = new HashSet<>();
        return prompts.stream().map(prompt -> {
            validName(prompt.name(), NAME, "MCP_PROMPT_CATALOG_INVALID");
            if (!promptNames.add(prompt.name())) {
                throw invalid("MCP_PROMPT_CATALOG_INVALID");
            }
            List<McpRemoteToolGateway.RemotePromptArgument> source = prompt.arguments() == null
                    ? List.of() : prompt.arguments();
            if (source.size() > MAX_ARGUMENTS) {
                throw invalid("MCP_PROMPT_SCHEMA_INVALID");
            }
            Set<String> argumentNames = new HashSet<>();
            List<ArgumentView> arguments = source.stream().map(argument -> {
                validName(argument.name(), ARGUMENT_NAME, "MCP_PROMPT_SCHEMA_INVALID");
                if (!argumentNames.add(argument.name())) {
                    throw invalid("MCP_PROMPT_SCHEMA_INVALID");
                }
                return new ArgumentView(
                        argument.name(),
                        bounded(argument.title(), 200, "MCP_PROMPT_SCHEMA_INVALID"),
                        bounded(argument.description(), 1_000, "MCP_PROMPT_SCHEMA_INVALID"),
                        argument.required());
            }).toList();
            return new PromptView(
                    prompt.name(),
                    bounded(prompt.title(), 200, "MCP_PROMPT_CATALOG_INVALID"),
                    bounded(prompt.description(), 1_000, "MCP_PROMPT_CATALOG_INVALID"),
                    arguments);
        }).toList();
    }

    private static void validateArguments(PromptView prompt, Map<String, String> values) {
        if (values.size() > MAX_ARGUMENTS) throw invalid("MCP_PROMPT_ARGUMENTS_INVALID");
        Set<String> declared = prompt.arguments().stream()
                .map(ArgumentView::name).collect(java.util.stream.Collectors.toSet());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!declared.contains(entry.getKey()) || entry.getValue() == null
                    || utf8(entry.getValue()) > MAX_ARGUMENT_BYTES
                    || SECRET.matcher(entry.getValue()).find()) {
                throw invalid("MCP_PROMPT_ARGUMENTS_INVALID");
            }
        }
        if (prompt.arguments().stream()
                .anyMatch(argument -> argument.required()
                        && !values.containsKey(argument.name()))) {
            throw invalid("MCP_PROMPT_ARGUMENTS_INVALID");
        }
    }

    private McpConnectionAuthorizationService.AuthorizedConnection authorized(
            String tenant, String owner, Binding binding) {
        validator.validate(new McpBindingValidationApplicationApi.ValidateCommand(
                tenant, owner, binding.installationId(), binding.connectionId(),
                binding.serverVersionId(), binding.capabilitySnapshotId(),
                binding.connectionRevision(), binding.snapshotSha256(),
                binding.allowedToolNames()));
        McpConnection connection = marketplace.findConnection(binding.connectionId())
                .orElseThrow(() -> invalid("MCP_PROMPT_BINDING_INVALID"));
        return authorization.authorize(connection);
    }

    private static void validName(String value, Pattern pattern, String code) {
        if (value == null || !pattern.matcher(value).matches()) throw invalid(code);
    }

    private static String bounded(String value, int max, String code) {
        if (value != null && value.length() > max) throw invalid(code);
        return value;
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static BusinessException invalid(String code) {
        return new BusinessException(
                "MCP Prompt is not safe for Context", HttpStatus.CONFLICT, code);
    }
}
