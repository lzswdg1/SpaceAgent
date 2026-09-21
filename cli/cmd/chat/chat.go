// Package chat 实现了聊天相关的命令，这是 CLI 的核心功能。
//
// chat.go 实现了交互式聊天模式——用户在终端中与 AI 助手实时对话。
package chat

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewChatCmd 创建聊天命令组。
func NewChatCmd(f *factory.Factory) *cobra.Command {
	var conversationID string
	var showThinking bool
	var agentID string

	cmd := &cobra.Command{
		Use:   "chat",
		Short: "与 AI 助手对话（交互式聊天）",
		Long:  "进入交互式聊天模式，与 SpaceAgent AI 助手实时对话。\n输入消息后按回车发送，输入 /quit 退出。",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runInteractiveChat(cmd.Context(), f, conversationID, agentID, showThinking)
		},
	}

	cmd.Flags().StringVarP(&conversationID, "conversation", "c", "", "对话 ID（恢复之前的对话）")
	cmd.Flags().StringVarP(&agentID, "agent", "a", "", "Agent ID（不传则使用当前账号第一个 Agent）")
	cmd.Flags().BoolVar(&showThinking, "show-thinking", false, "显示模型返回的思考过程（仅模型支持时有效）")
	cmd.AddCommand(NewSendCmd(f))
	cmd.AddCommand(NewHistoryCmd(f))
	cmd.AddCommand(NewConversationsCmd(f))
	cmd.AddCommand(NewCloseCmd(f))
	cmd.AddCommand(NewUsageCmd(f))

	return cmd
}

// runInteractiveChat 是交互式聊天的核心实现。
func runInteractiveChat(ctx context.Context, f *factory.Factory, conversationID, agentID string, showThinking bool) error {
	out := f.IOStreams.Out

	cfg, err := f.Config()
	if err != nil || !cfg.IsLoggedIn() {
		output.ErrorWithHint(out, "请先登录", "使用 spaceagent auth login 登录")
		return fmt.Errorf("未登录")
	}

	c, err := f.Client()
	if err != nil {
		output.Error(out, "无法创建客户端: %s", err)
		return err
	}

	currentModelId := ""
	resolvedAgentID, err := resolveChatAgentID(c, agentID)
	if err != nil {
		output.ErrorWithHint(out, "没有可用 Agent", "请先使用 spaceagent agent create 创建 Agent，或通过 spaceagent chat --agent <agentId> 指定")
		return err
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	defer signal.Stop(sigChan)

	// Ctrl+C 时关闭 stdin，使 scanner.Scan() 返回 false，主循环正常退出
	go func() {
		<-sigChan
		os.Stdin.Close()
	}()

	printChatBanner(out, conversationID, cfg.Username, resolvedAgentID)

	scanner := bufio.NewScanner(f.IOStreams.In)
	scanner.Buffer(make([]byte, 0, 64*1024), 64*1024)

	for {
		fmt.Fprintf(out, "\n%s ", color.CyanString("你 >"))

		if !scanner.Scan() {
			if err := scanner.Err(); err != nil {
				output.Error(out, "读取输入失败: %s", err)
			}
			printChatFarewell(out, conversationID)
			return nil
		}

		userInput := strings.TrimSpace(scanner.Text())
		if userInput == "" {
			continue
		}

		if strings.HasPrefix(userInput, "/") {
			if handleThinkingToggle(out, userInput, &showThinking) {
				continue
			}
			if userInput == "/model" || strings.HasPrefix(userInput, "/model ") {
				currentModelId = handleModelSwitch(out, c, userInput)
				continue
			}
			if handleLocalCommand(out, userInput, conversationID) {
				return nil
			}
			continue
		}

		fmt.Fprintln(out)
		var hasContent bool
		thinking := output.NewThinkingStream(out)
		err := c.PostStreamWithContext(ctx, "/api/v1/chat/messages/stream", client.ChatMessageRequest{
			ConversationId: conversationID,
			AgentId:        resolvedAgentID,
			Message:        userInput,
			ModelId:        currentModelId,
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
					conversationID = cid
				}
				output.RAGSummary(out, done.RagUsed, done.RetrievedChunkCount, done.Citations)
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
				printChatFarewell(out, conversationID)
				return nil
			}
			if apiErr, ok := err.(*client.APIError); ok && apiErr.StatusCode == 401 {
				output.ErrorWithHint(out, "登录已过期", "请使用 spaceagent auth login 重新登录")
				return err
			}
			if apiErr, ok := err.(*client.APIError); ok {
				output.Error(out, "发送失败: %s", output.FriendlyAIError(apiErr.Msg))
			} else {
				output.Error(out, "发送失败: %s", output.FriendlyAIError(err.Error()))
			}
			continue
		}
	}
}

func handleThinkingToggle(out io.Writer, input string, showThinking *bool) bool {
	cmd := strings.ToLower(strings.TrimSpace(input))
	if cmd != "/thinking" && cmd != "/think" {
		return false
	}

	*showThinking = !*showThinking
	if *showThinking {
		output.Info(out, "思考过程显示已开启")
	} else {
		output.Info(out, "思考过程显示已关闭")
	}
	return true
}

// printChatBanner 显示聊天模式的欢迎横幅。
func printChatBanner(out io.Writer, conversationID, username, agentID string) {
	fmt.Fprintln(out)
	fmt.Fprintln(out, color.New(color.FgCyan, color.Bold).Sprint("  ╔══════════════════════════════════════╗"))
	fmt.Fprintln(out, color.New(color.FgCyan, color.Bold).Sprint("  ║   SpaceAgent AI Agent 平台           ║"))
	fmt.Fprintln(out, color.New(color.FgCyan, color.Bold).Sprint("  ╚══════════════════════════════════════╝"))
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("用户:"), username)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("Agent:"), truncateConvID(agentID))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("对话:"), truncateConvID(conversationID))
	fmt.Fprintln(out)
	fmt.Fprintln(out, color.HiBlackString("  输入消息开始对话，/help 查看帮助，/model 切换模型，/quit 退出"))
	fmt.Fprintln(out)
}

func resolveChatAgentID(c *client.Client, preferredAgentID string) (string, error) {
	if strings.TrimSpace(preferredAgentID) != "" {
		return strings.TrimSpace(preferredAgentID), nil
	}

	var resp client.ApiResponse[[]client.AgentResponse]
	if err := c.Get("/api/v1/agents", &resp); err != nil {
		return "", err
	}
	if len(resp.Data) == 0 {
		return "", fmt.Errorf("no agents found")
	}
	return resp.Data[0].ID, nil
}

// handleLocalCommand 处理以 / 开头的本地命令。
// 返回 true 表示应该退出聊天。
func handleLocalCommand(out io.Writer, input, conversationID string) bool {
	cmd := strings.ToLower(strings.TrimSpace(input))

	switch cmd {
	case "/quit", "/exit", "/q":
		printChatFarewell(out, conversationID)
		return true

	case "/id":
		fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("对话 ID:"), conversationID)
		output.Hint(out, "使用 spaceagent chat -c %s 恢复此对话", conversationID)

	case "/help", "/h":
		fmt.Fprintln(out)
		fmt.Fprintln(out, color.CyanString("  可用命令:"))
		fmt.Fprintln(out, "    /quit, /exit, /q  退出聊天")
		fmt.Fprintln(out, "    /id               显示当前对话 ID")
		fmt.Fprintln(out, "    /model            列出可用模型并切换")
		fmt.Fprintln(out, "    /thinking         开关模型思考过程显示")
		fmt.Fprintln(out, "    /help, /h         显示此帮助信息")
		fmt.Fprintln(out)

	default:
		output.Warning(out, "未知命令: %s（输入 /help 查看帮助）", cmd)
	}

	return false
}

// renderAssistantReply 渲染助手的回复，包括消息内容和元数据。
func renderAssistantReply(out io.Writer, data client.ChatMessageResponse) {
	fmt.Fprintln(out)

	lines := strings.Split(data.AssistantMessage, "\n")
	for _, line := range lines {
		if strings.TrimSpace(line) == "" {
			fmt.Fprintf(out, "  %s\n", color.GreenString("┃"))
		} else {
			fmt.Fprintf(out, "  %s %s\n", color.GreenString("┃"), line)
		}
	}

	var metaParts []string

	if data.DetectedMood != "" {
		metaParts = append(metaParts, output.MoodTag(data.DetectedMood))
	}

	metaParts = append(metaParts, fmt.Sprintf("安全: %s", output.RiskBadge(data.RiskLevel)))

	if data.RecalledMemoryCount > 0 {
		metaParts = append(metaParts, color.BlueString("🧠 记忆: %d条召回", data.RecalledMemoryCount))
	}

	if data.MemoryUpdated {
		metaParts = append(metaParts, color.MagentaString("💾 记忆已更新"))
	}

	if len(metaParts) > 0 {
		fmt.Fprintf(out, "  %s %s\n", color.GreenString("┃"), strings.Join(metaParts, "  ┃  "))
	}

	if data.SuggestedFollowUp != "" {
		fmt.Fprintf(out, "  %s %s\n", color.GreenString("┃"),
			color.HiBlackString("💡 建议: %s", data.SuggestedFollowUp))
	}
}

// printChatFarewell 显示退出聊天时的告别信息。
func printChatFarewell(out io.Writer, conversationID string) {
	fmt.Fprintln(out)
	output.Info(out, "对话结束")
	output.Hint(out, "对话 ID: %s", conversationID)
	output.Hint(out, "使用 spaceagent chat -c %s 恢复此对话", conversationID)
	fmt.Fprintln(out)
}

// truncateConvID 安全截断对话 ID 用于显示。
func truncateConvID(id string) string {
	if strings.TrimSpace(id) == "" {
		return "新对话"
	}
	if len(id) > 8 {
		return id[:8] + "..."
	}
	return id
}

// handleModelSwitch 处理 /model 命令，列出可用模型并切换。
func handleModelSwitch(out io.Writer, c *client.Client, input string) string {
	// 如果直接指定了模型 ID: /model claude-sonnet-4-20250514
	parts := strings.Fields(input)
	if len(parts) > 1 {
		modelId := parts[1]
		fmt.Fprintf(out, "  %s %s\n", color.GreenString("✓"), "已切换到模型: "+modelId)
		return modelId
	}

	// 列出可用模型
	type AvailableModel struct {
		ID               string `json:"id"`
		ModelId          string `json:"modelId"`
		DisplayName      string `json:"displayName"`
		MaxContextTokens int    `json:"maxContextTokens"`
	}

	var resp client.ApiResponse[[]AvailableModel]
	err := c.Get("/api/v1/models/available", &resp)
	if err != nil {
		output.Error(out, "获取模型列表失败: %s", err)
		return ""
	}

	if len(resp.Data) == 0 {
		output.Info(out, "暂无可用模型，使用默认配置")
		output.Hint(out, "使用 spaceagent provider add 添加提供商")
		return ""
	}

	fmt.Fprintln(out)
	fmt.Fprintln(out, color.CyanString("  可用模型:"))
	fmt.Fprintf(out, "  %s  %s\n", color.HiBlackString("0."), "默认模型（.env 配置）")
	for i, m := range resp.Data {
		fmt.Fprintf(out, "  %s  %s (%s)\n",
			color.HiBlackString(fmt.Sprintf("%d.", i+1)),
			m.DisplayName, m.ModelId)
	}
	fmt.Fprintln(out)
	output.Hint(out, "输入编号选择，或直接输入 /model <modelId> 切换")

	return ""
}
