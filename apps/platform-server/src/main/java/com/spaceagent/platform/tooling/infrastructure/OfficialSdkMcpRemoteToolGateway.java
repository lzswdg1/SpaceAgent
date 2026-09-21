package com.spaceagent.platform.tooling.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionProbeGateway;
import com.spaceagent.platform.tooling.domain.McpRemoteEndpointPolicy;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.shared.exception.BusinessException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Component
public class OfficialSdkMcpRemoteToolGateway
        implements McpRemoteToolGateway, McpConnectionProbeGateway {
    private static final int MAX_TOOLS = 100;
    private static final int MAX_TOOL_PAGES = 20;
    private static final int MAX_RESOURCES = 100;
    private static final int MAX_PROMPTS = 100;
    private static final int MAX_PROMPT_PAGES = 20;
    private static final Pattern HEADER =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade",
            "accept", "content-type", "authorization", "mcp-session-id",
            "mcp-protocol-version");

    private final McpRemoteEndpointPolicy policy;
    private final ObjectMapper json;

    public OfficialSdkMcpRemoteToolGateway(
            McpRemoteEndpointPolicy policy, ObjectMapper json) {
        this.policy = policy;
        this.json = json;
    }

    @Override
    public ProbeResult probe(McpConnection connection, Map<String, String> authorization) {
        ClientHandle handle = client(connection, authorization);
        try (McpSyncClient client = handle.client()) {
            McpSchema.InitializeResult initialized = client.initialize();
            handle.rejectTasks();
            List<McpSchema.Tool> tools = allTools(client);
            McpSchema.Implementation server = initialized.serverInfo();
            return new ProbeResult(
                    initialized.protocolVersion(),
                    server == null ? "unknown" : server.name(),
                    server == null ? null : server.title(),
                    server == null ? "unknown" : server.version(),
                    server == null ? null : server.description(),
                    json.convertValue(initialized.capabilities(),
                            new TypeReference<Map<String, Object>>() { }),
                    tools.stream().map(this::probeTool).toList());
        } catch (RuntimeException error) {
            handle.rejectTasks();
            throw failure("MCP connection probe failed", error);
        }
    }

    @Override
    public List<RemoteTool> listTools(
            McpConnection connection, Map<String, String> authorization) {
        return probe(connection, authorization).tools().stream()
                .map(tool -> new RemoteTool(
                        tool.name(), tool.description(), tool.inputSchema(),
                        tool.readOnly(), tool.destructive()))
                .toList();
    }

    @Override
    public RemoteResult callTool(
            McpConnection connection,
            Map<String, String> authorization,
            String tool,
            Map<String, Object> arguments) {
        ClientHandle handle = client(connection, authorization);
        try (McpSyncClient client = handle.client()) {
            client.initialize();
            handle.rejectTasks();
            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(
                            tool, arguments == null ? Map.of() : arguments));
            handle.rejectTasks();
            String text = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .findFirst()
                    .orElseGet(() -> {
                        try {
                            return json.writeValueAsString(result.content());
                        } catch (Exception error) {
                            return "[]";
                        }
                    });
            return new RemoteResult(
                    Boolean.TRUE.equals(result.isError()), result.structuredContent(), text);
        } catch (RuntimeException error) {
            handle.rejectTasks();
            throw failure("MCP tool call failed", error);
        }
    }

    @Override public List<RemoteResource> listResources(McpConnection connection,Map<String,String> authorization){
        ClientHandle handle=client(connection,authorization);try(McpSyncClient client=handle.client()){client.initialize();handle.rejectTasks();List<RemoteResource> values=new ArrayList<>();Set<String> cursors=new HashSet<>();String cursor=null;
            for(int page=0;page<20;page++){var result=client.listResources(cursor);if(result.resources()!=null)for(var r:result.resources()){values.add(new RemoteResource(r.uri(),r.name(),r.title(),r.description(),r.mimeType(),r.size()));if(values.size()>MAX_RESOURCES)throw new IllegalStateException("MCP resource catalog exceeds limit");}String next=result.nextCursor();if(next==null||next.isBlank())return List.copyOf(values);if(!cursors.add(next))throw new IllegalStateException("MCP resource cursor repeated");cursor=next;}throw new IllegalStateException("MCP resource page count exceeds limit");
        }catch(RuntimeException e){handle.rejectTasks();throw failure("MCP resource list failed",e);}}

    @Override public RemoteResourceContent readResource(McpConnection connection,Map<String,String> authorization,String uri){
        ClientHandle handle=client(connection,authorization);try(McpSyncClient client=handle.client()){client.initialize();handle.rejectTasks();var result=client.readResource(new McpSchema.ReadResourceRequest(uri));handle.rejectTasks();if(result.contents()==null||result.contents().size()!=1)throw new IllegalStateException("MCP resource content count invalid");var content=result.contents().getFirst();if(content instanceof McpSchema.TextResourceContents text)return new RemoteResourceContent(text.uri(),text.mimeType(),text.text(),false);if(content instanceof McpSchema.BlobResourceContents blob)return new RemoteResourceContent(blob.uri(),blob.mimeType(),blob.blob(),true);throw new IllegalStateException("MCP resource content unsupported");
        }catch(RuntimeException e){handle.rejectTasks();throw failure("MCP resource read failed",e);}}

    @Override
    public List<RemotePrompt> listPrompts(
            McpConnection connection, Map<String, String> authorization) {
        ClientHandle handle = client(connection, authorization);
        try (McpSyncClient client = handle.client()) {
            client.initialize();
            handle.rejectTasks();
            List<RemotePrompt> prompts = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            for (int page = 0; page < MAX_PROMPT_PAGES; page++) {
                McpSchema.ListPromptsResult result = client.listPrompts(cursor);
                if (result.prompts() != null) {
                    for (McpSchema.Prompt prompt : result.prompts()) {
                        List<RemotePromptArgument> arguments = prompt.arguments() == null
                                ? List.of()
                                : prompt.arguments().stream()
                                        .map(argument -> new RemotePromptArgument(
                                                argument.name(), argument.title(),
                                                argument.description(),
                                                Boolean.TRUE.equals(argument.required())))
                                        .toList();
                        prompts.add(new RemotePrompt(
                                prompt.name(), prompt.title(), prompt.description(), arguments));
                        if (prompts.size() > MAX_PROMPTS) {
                            throw new IllegalStateException("MCP prompt catalog exceeds limit");
                        }
                    }
                }
                String next = result.nextCursor();
                if (next == null || next.isBlank()) return List.copyOf(prompts);
                if (!cursors.add(next)) {
                    throw new IllegalStateException("MCP prompt cursor repeated");
                }
                cursor = next;
            }
            throw new IllegalStateException("MCP prompt page count exceeds limit");
        } catch (RuntimeException error) {
            handle.rejectTasks();
            throw failure("MCP prompt list failed", error);
        }
    }

    @Override
    public RemotePromptResult getPrompt(
            McpConnection connection,
            Map<String, String> authorization,
            String name,
            Map<String, String> arguments) {
        ClientHandle handle = client(connection, authorization);
        try (McpSyncClient client = handle.client()) {
            client.initialize();
            handle.rejectTasks();
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            if (arguments != null) values.putAll(arguments);
            McpSchema.GetPromptResult result = client.getPrompt(
                    new McpSchema.GetPromptRequest(name, values));
            handle.rejectTasks();
            List<RemotePromptMessage> messages = result.messages() == null
                    ? List.of()
                    : result.messages().stream().map(message -> {
                        if (message.content() instanceof McpSchema.TextContent text) {
                            return new RemotePromptMessage(
                                    message.role().name(), text.text(), true);
                        }
                        return new RemotePromptMessage(
                                message.role().name(), null, false);
                    }).toList();
            return new RemotePromptResult(result.description(), messages);
        } catch (RuntimeException error) {
            handle.rejectTasks();
            throw failure("MCP prompt get failed", error);
        }
    }

    private List<McpSchema.Tool> allTools(McpSyncClient client) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int pageNumber = 0; pageNumber < MAX_TOOL_PAGES; pageNumber++) {
            McpSchema.ListToolsResult page = client.listTools(cursor);
            if (page.tools() != null) tools.addAll(page.tools());
            if (tools.size() > MAX_TOOLS) {
                throw new IllegalStateException("MCP tool catalog exceeds limit");
            }
            String next = page.nextCursor();
            if (next == null || next.isBlank()) return List.copyOf(tools);
            if (!cursors.add(next)) {
                throw new IllegalStateException("MCP tool pagination cursor repeated");
            }
            cursor = next;
        }
        throw new IllegalStateException("MCP tool catalog page count exceeds limit");
    }

    private ProbeTool probeTool(McpSchema.Tool tool) {
        McpSchema.ToolAnnotations annotations = tool.annotations();
        return new ProbeTool(
                tool.name(), tool.title(), tool.description() == null ? "" : tool.description(),
                tool.inputSchema() == null ? Map.of() : tool.inputSchema(),
                tool.outputSchema() == null ? Map.of() : tool.outputSchema(),
                annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint()),
                annotations != null && Boolean.TRUE.equals(annotations.destructiveHint()),
                annotations != null && Boolean.TRUE.equals(annotations.idempotentHint()),
                annotations != null && Boolean.TRUE.equals(annotations.openWorldHint()));
    }

    private ClientHandle client(
            McpConnection connection, Map<String, String> authorization) {
        URI uri = policy.validate(connection.endpointUrl());
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = (uri.getRawPath() == null || uri.getRawPath().isBlank()
                ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        TaskDetectingJsonMapper mapper = new TaskDetectingJsonMapper(json);
        HttpClientStreamableHttpTransport transport =
                HttpClientStreamableHttpTransport.builder(base)
                        .endpoint(endpoint)
                        .jsonMapper(mapper)
                        .connectTimeout(Duration.ofSeconds(5))
                        .maxResponseSize(2_000_000)
                        .httpRequestCustomizer((builder, method, requestUri, body, context) ->
                                headers(builder, authorization))
                        .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(20))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .clientInfo(new McpSchema.Implementation("spaceagent", "1.0"))
                .build();
        return new ClientHandle(client, mapper);
    }

    private static RuntimeException failure(String message, RuntimeException error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof BusinessException business
                    && "MCP_TASKS_UNSUPPORTED".equals(business.getCode())) {
                return business;
            }
        }
        return new IllegalStateException(message, error);
    }

    private static BusinessException tasksUnsupported() {
        return new BusinessException(
                "MCP Tasks are not supported", HttpStatus.CONFLICT,
                "MCP_TASKS_UNSUPPORTED");
    }

    private record ClientHandle(McpSyncClient client, TaskDetectingJsonMapper mapper) {
        private void rejectTasks() {
            if (mapper.tasksSeen()) throw tasksUnsupported();
        }
    }

    private static final class TaskDetectingJsonMapper implements McpJsonMapper {
        private static final Set<String> TASK_STATUSES = Set.of(
                "working", "input_required", "completed", "failed", "cancelled");
        private final ObjectMapper detector;
        private final McpJsonMapper delegate;
        private final AtomicBoolean tasksSeen = new AtomicBoolean();

        private TaskDetectingJsonMapper(ObjectMapper source) {
            this.detector = source.copy();
            this.delegate = new JacksonMcpJsonMapper(source.copy());
        }

        @Override
        public <T> T readValue(String content, Class<T> type) throws IOException {
            inspect(detector.readTree(content));
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T readValue(byte[] content, Class<T> type) throws IOException {
            inspect(detector.readTree(content));
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T readValue(String content, TypeRef<T> type) throws IOException {
            inspect(detector.readTree(content));
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
            inspect(detector.readTree(content));
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T convertValue(Object value, Class<T> type) {
            return delegate.convertValue(value, type);
        }

        @Override
        public <T> T convertValue(Object value, TypeRef<T> type) {
            return delegate.convertValue(value, type);
        }

        @Override
        public String writeValueAsString(Object value) throws IOException {
            return delegate.writeValueAsString(value);
        }

        @Override
        public byte[] writeValueAsBytes(Object value) throws IOException {
            return delegate.writeValueAsBytes(value);
        }

        private void inspect(JsonNode root) {
            if (root == null) return;
            JsonNode result = root.path("result");
            JsonNode capabilities = result.path("capabilities");
            boolean advertised = capabilities.has("tasks")
                    || capabilities.path("extensions").has("io.modelcontextprotocol/tasks");
            boolean extensionResult = "task".equals(result.path("resultType").asText());
            boolean coreResult = taskShape(result);
            boolean nestedResult = taskShape(result.path("task"));
            if (advertised || extensionResult || coreResult || nestedResult) {
                tasksSeen.set(true);
            }
        }

        private boolean tasksSeen() {
            return tasksSeen.get();
        }

        private static boolean taskShape(JsonNode value) {
            return value.isObject() && value.path("taskId").isTextual()
                    && TASK_STATUSES.contains(value.path("status").asText())
                    && !value.has("content");
        }
    }

    private static void headers(
            java.net.http.HttpRequest.Builder builder, Map<String, String> authorization) {
        String token = authorization.getOrDefault(
                "access_token", authorization.get("token"));
        if (token != null && !token.isBlank() && safe(token)) {
            builder.header("Authorization", "Bearer " + token);
        }
        for (Map.Entry<String, String> entry : authorization.entrySet()) {
            if (!entry.getKey().startsWith("header:")
                    || entry.getKey().length() <= 7 || !safe(entry.getValue())) {
                continue;
            }
            String name = entry.getKey().substring(7);
            if (!HEADER.matcher(name).matches()
                    || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsafe MCP header");
            }
            builder.header(name, entry.getValue());
        }
    }

    private static boolean safe(String value) {
        return value != null && !value.contains("\r") && !value.contains("\n")
                && value.length() <= 4_096;
    }
}
