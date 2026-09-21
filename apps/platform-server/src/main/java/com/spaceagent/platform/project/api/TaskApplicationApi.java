package com.spaceagent.platform.project.api;

import java.util.List;

/** Public tenant- and user-aware Task foundation use cases. */
public interface TaskApplicationApi {

    TaskView createTask(CreateTaskCommand command);

    TaskView createOrGetChatRootTask(CreateChatRootTaskCommand command);

    TaskView getChatTask(GetChatTaskQuery query);

    List<TaskView> listChatTasks(ListChatTasksQuery query);

    TaskView transitionChatTask(TransitionChatTaskCommand command);

    TaskView getTask(GetTaskQuery query);

    List<TaskView> listTasks(ListTasksQuery query);

    TaskView updateTask(UpdateTaskCommand command);

    TaskView transitionTask(TransitionTaskCommand command);
}
