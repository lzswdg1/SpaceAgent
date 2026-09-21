package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.ListChatTasksQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/tasks")
public class PlatformChatTaskHttpController {
    private final TaskApplicationApi tasks;

    public PlatformChatTaskHttpController(TaskApplicationApi tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<List<TaskView>> list(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        return ApiResponse.ok(tasks.listChatTasks(new ListChatTasksQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), conversationId, limit)));
    }
}
