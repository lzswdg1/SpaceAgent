// send.go 实现了单条消息发送命令。
//
// 用法:
//
//	spaceagent chat send "你好"                    # 发送到新对话
//	spaceagent chat send -c <对话ID> "继续聊聊"    # 发送到已有对话
package chat

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewSendCmd 创建单条消息发送命令。
func NewSendCmd(f *factory.Factory) *cobra.Command {
	var conversationID string
	var agentID string
	var modelID string
	var imagePaths []string
	var showThinking bool

	cmd := &cobra.Command{
		Use:   "send [消息内容]",
		Short: "发送单条消息",
		Long:  "发送一条消息并显示回复，然后退出。\n适合快速交互或脚本使用。",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runSend(cmd.Context(), f, conversationID, agentID, modelID, imagePaths, showThinking, args[0])
		},
	}

	cmd.Flags().StringVarP(&conversationID, "conversation", "c", "", "对话 ID（发送到已有对话）")
	cmd.Flags().StringVarP(&agentID, "agent", "a", "", "Agent ID（不传则使用当前账号第一个 Agent）")
	cmd.Flags().StringVarP(&modelID, "model", "m", "", "指定模型 ID")
	cmd.Flags().StringSliceVarP(&imagePaths, "image", "i", nil, "附带图片路径（当前微服务后端暂未开放通用图片上传）")
	cmd.Flags().BoolVar(&showThinking, "show-thinking", false, "显示模型返回的思考过程（仅模型支持时有效）")

	return cmd
}

// runSend 执行单条消息发送。
func runSend(ctx context.Context, f *factory.Factory, conversationID, agentID, modelID string, imagePaths []string, showThinking bool, message string) error {
	out := f.IOStreams.Out

	cfg, err := f.Config()
	if err != nil || !cfg.IsLoggedIn() {
		output.ErrorWithHint(out, "请先登录", "使用 spaceagent auth login 登录")
		return fmt.Errorf("未登录")
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	if len(imagePaths) > 0 {
		output.ErrorWithHint(out,
			"当前微服务后端暂未开放通用图片上传接口",
			"知识库文档请使用 spaceagent kb upload；聊天图片能力需要后端 file-service 或图片接口恢复后再启用")
		return fmt.Errorf("image upload is not supported by current backend")
	}

	resolvedAgentID, err := resolveChatAgentID(c, agentID)
	if err != nil {
		output.ErrorWithHint(out, "没有可用 Agent", "请先使用 spaceagent agent create 创建 Agent，或通过 --agent <agentId> 指定")
		return err
	}

	fmt.Fprintln(out)

	var finalConvID string
	var hasContent bool
	thinking := output.NewThinkingStream(out)
	err = c.PostStreamWithContext(ctx, "/api/v1/chat/messages/stream", client.ChatMessageRequest{
		ConversationId: conversationID,
		AgentId:        resolvedAgentID,
		Message:        strings.TrimSpace(message),
		ModelId:        modelID,
	}, func(event client.SSEEvent) error {
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
				thinking.Write(stringValue(data["reasoningContent"]))
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
			renderUsageSummary(out, extractUsageSummary(data))
		case "error":
			thinking.Close()
			var data map[string]string
			json.Unmarshal([]byte(event.Data), &data)
			output.Error(out, "AI 生成失败: %s", output.FriendlyAIError(data["message"]))
		}
		return nil
	})

	if err != nil {
		if ctx.Err() != nil {
			thinking.Close()
			output.Info(out, "请求已取消")
			return nil
		}
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "发送失败: %s", output.FriendlyAIError(apiErr.Msg))
		} else {
			output.ErrorWithHint(out, "无法连接到服务器", "请先运行 spaceagent health 检查服务器状态")
		}
		return err
	}

	if finalConvID == "" {
		finalConvID = conversationID
	}

	fmt.Fprintln(out)
	output.Hint(out, "对话 ID: %s", finalConvID)
	output.Hint(out, "使用 spaceagent chat -c %s 继续对话", finalConvID)

	return nil
}
