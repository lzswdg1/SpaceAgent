package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.ChatTaskPlanActionCommand;
import com.spaceagent.platform.project.api.GetChatTaskPlanQuery;
import com.spaceagent.platform.project.api.ListChatTaskPlansQuery;
import com.spaceagent.platform.project.api.TaskPlanAction;
import com.spaceagent.platform.project.api.TaskPlanApplicationApi;
import com.spaceagent.platform.project.api.TaskPlanView;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Owner-scoped review lifecycle for Java-persisted Chat Planner proposals. */
@RestController
@RequestMapping("/api/v1/chat/conversations/{conversationId}/tasks/{rootTaskId}/plans")
public class PlatformChatTaskPlanHttpController {

    private final TaskPlanApplicationApi taskPlanApi;

    public PlatformChatTaskPlanHttpController(TaskPlanApplicationApi taskPlanApi) {
        this.taskPlanApi = taskPlanApi;
    }

    @GetMapping
    public ApiResponse<List<TaskPlanView>> list(
            @PathVariable String conversationId,
            @PathVariable String rootTaskId,
            Authentication authentication) {
        return ApiResponse.ok(taskPlanApi.listChatPlans(new ListChatTaskPlansQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), conversationId, rootTaskId)));
    }

    @GetMapping("/{planId}")
    public ApiResponse<TaskPlanView> get(
            @PathVariable String conversationId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return ApiResponse.ok(taskPlanApi.getChatPlan(new GetChatTaskPlanQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), conversationId, rootTaskId, planId)));
    }

    @PostMapping("/{planId}/approve")
    public ApiResponse<TaskPlanView> approve(
            @PathVariable String conversationId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(
                conversationId, rootTaskId, planId, TaskPlanAction.APPROVE, authentication);
    }

    @PostMapping("/{planId}/activate")
    public ApiResponse<TaskPlanView> activate(
            @PathVariable String conversationId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(
                conversationId, rootTaskId, planId, TaskPlanAction.ACTIVATE, authentication);
    }

    @PostMapping("/{planId}/cancel")
    public ApiResponse<TaskPlanView> cancel(
            @PathVariable String conversationId,
            @PathVariable String rootTaskId,
            @PathVariable String planId,
            Authentication authentication) {
        return transition(
                conversationId, rootTaskId, planId, TaskPlanAction.CANCEL, authentication);
    }

    private ApiResponse<TaskPlanView> transition(
            String conversationId, String rootTaskId, String planId,
            TaskPlanAction action, Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(taskPlanApi.transitionChatPlan(new ChatTaskPlanActionCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), conversationId, rootTaskId,
                planId, action)));
    }
}
