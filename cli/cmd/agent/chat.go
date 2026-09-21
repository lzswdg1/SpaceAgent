// chat.go 实现了 Agent 流式聊天命令。
//
// 用法:
//
//	spaceagent agent chat <agentId> "你好"           # 与指定 Agent 对话
//	spaceagent agent chat -c <对话ID> <agentId> "继续" # 继续已有对话
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewAgentChatCmd 创建 Agent 流式聊天命令。
func NewAgentChatCmd(f *factory.Factory) *cobra.Command {
	var conversationID string
	var showThinking bool

	cmd := &cobra.Command{
		Use:   "chat <agentId> <message>",
		Short: "与指定 Agent 进行流式对话",
		Long:  "向指定 Agent 发送消息并以流式方式显示回复。\n支持通过 -c 指定对话 ID 继续已有对话。",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runAgentChat(cmd.Context(), f, args[0], args[1], conversationID, showThinking)
		},
	}

	cmd.Flags().StringVarP(&conversationID, "conversation", "c", "", "对话 ID（继续已有对话）")
	cmd.Flags().BoolVar(&showThinking, "show-thinking", false, "显示模型返回的思考过程（仅模型支持时有效）")
	return cmd
}

// runAgentChat 执行 Agent 流式聊天。
func runAgentChat(ctx context.Context, f *factory.Factory, agentID, message, conversationID string, showThinking bool) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	fmt.Fprintln(out)

	var finalConvID string
	var hasContent bool
	thinking := output.NewThinkingStream(out)
	err = c.PostStreamWithContext(
		ctx,
		"/api/v1/chat/messages/stream",
		client.ChatMessageRequest{
			AgentId:        strings.TrimSpace(agentID),
			Message:        strings.TrimSpace(message),
			ConversationId: conversationID,
		},
		func(event client.SSEEvent) error {
			switch event.Event {
			case "thinking":
				if showThinking {
					thinking.Write(event.Content())
				}
			case "delta":
				thinking.Close()
				var data map[string]string
				json.Unmarshal([]byte(event.Data), &data)
				if !hasContent {
					fmt.Fprintf(out, "  %s ", color.GreenString("┃"))
					hasContent = true
				}
				fmt.Fprint(out, data["content"])
			case "tool_call":
				thinking.Close()
				output.ToolCall(out, event.Content())
			case "tool_result":
				thinking.Close()
				output.ToolResult(out, event.Content())
			case "done":
				if hasContent {
					fmt.Fprintln(out)
				}
				var data map[string]interface{}
				json.Unmarshal([]byte(event.Data), &data)
				var done client.ChatDoneEvent
				json.Unmarshal([]byte(event.Data), &done)
				if showThinking && !thinking.Started() {
					thinking.Write(agentStringValue(data["reasoningContent"]))
				}
				thinking.Close()
				if cid, ok := data["conversationId"].(string); ok {
					finalConvID = cid
				}
				output.RAGSummary(out, done.RagUsed, done.RetrievedChunkCount, done.Citations)
				// 渲染元数据
				var metaParts []string
				if mood, ok := data["detectedMood"].(string); ok && mood != "" {
					metaParts = append(metaParts, output.MoodTag(mood))
				}
				if risk, ok := data["riskLevel"].(string); ok {
					metaParts = append(metaParts, fmt.Sprintf("安全: %s", output.RiskBadge(risk)))
				}
				if len(metaParts) > 0 {
					fmt.Fprintf(out, "  %s %s\n", color.GreenString("┃"), strings.Join(metaParts, "  ┃  "))
				}
				// Token 用量汇总
				renderAgentUsage(out, data)
			case "error":
				thinking.Close()
				var data map[string]string
				json.Unmarshal([]byte(event.Data), &data)
				output.Error(out, "Agent 回复失败: %s", output.FriendlyAIError(data["message"]))
			}
			return nil
		},
	)

	if err != nil {
		if ctx.Err() != nil {
			thinking.Close()
			output.Info(out, "请求已取消")
			return nil
		}
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "请求失败: %s", output.FriendlyAIError(apiErr.Msg))
		} else {
			output.ErrorWithHint(out, "无法连接到服务器", "请先运行 spaceagent health 检查服务器状态")
		}
		return err
	}

	fmt.Fprintln(out)
	if finalConvID != "" {
		output.Hint(out, "对话 ID: %s", finalConvID)
		output.Hint(out, "使用 spaceagent agent chat -c %s %s \"继续聊\" 继续对话", finalConvID, agentID)
	}

	return nil
}

func agentStringValue(value interface{}) string {
	if s, ok := value.(string); ok {
		return s
	}
	return ""
}

// renderAgentUsage 渲染 Agent 聊天的 Token 用量信息。
func renderAgentUsage(out io.Writer, data map[string]interface{}) {
	inputTokens := intFromInterface(data["inputTokenCount"])
	outputTokens := intFromInterface(data["outputTokenCount"])
	totalTokens := intFromInterface(data["totalTokenCount"])

	if totalTokens <= 0 && inputTokens <= 0 && outputTokens <= 0 {
		return
	}

	fmt.Fprintf(out, "  %s %s\n",
		color.GreenString("•"),
		color.HiBlackString("tokens in:%d out:%d total:%d",
			inputTokens, outputTokens, totalTokens))
}

// intFromInterface 从 interface{} 提取 int 值。
func intFromInterface(value interface{}) int {
	switch v := value.(type) {
	case float64:
		return int(v)
	case float32:
		return int(v)
	case int:
		return v
	case int64:
		return int(v)
	default:
		return 0
	}
}
