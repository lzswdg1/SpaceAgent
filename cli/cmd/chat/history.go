// history.go 实现了查看对话历史的命令。
//
// 用法: spaceagent chat history <对话ID>
package chat

import (
	"fmt"
	"io"
	"strings"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewHistoryCmd 创建查看对话历史的命令。
func NewHistoryCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "history [对话ID]",
		Short: "查看对话历史",
		Long:  "显示指定对话的所有消息记录。\n对话 ID 可以在聊天时通过 /id 命令获取。",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runHistory(f, args[0])
		},
	}
}

// runHistory 执行查看对话历史的逻辑。
func runHistory(f *factory.Factory, conversationID string) error {
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

	s := output.StartSpinner(" 加载对话历史...")

	var resp client.ApiResponse[client.ConversationViewResponse]
	err = c.Get("/api/v1/chat/conversations/"+conversationID, &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "对话不存在: %s", conversationID)
			case 403:
				output.Error(out, "无权查看此对话")
			default:
				output.Error(out, "获取对话历史失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	data := resp.Data

	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("对话 ID:"), data.ConversationId)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("状态:"), data.Status)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("开始时间:"), output.FormatTime(data.StartedAt))
	fmt.Fprintf(out, "  %s %d 条消息\n", color.HiBlackString("消息数:"), len(data.Messages))
	fmt.Fprintln(out)
	fmt.Fprintln(out, color.HiBlackString("  ─────────────────────────────────────"))
	fmt.Fprintln(out)

	for _, msg := range data.Messages {
		renderHistoryMessage(out, msg)
	}

	return nil
}

// renderHistoryMessage 渲染历史消息中的单条消息。
func renderHistoryMessage(out io.Writer, msg client.ConversationMessageItem) {
	timestamp := color.HiBlackString(output.FormatTime(msg.CreatedAt))

	if msg.Role == "USER" {
		fmt.Fprintf(out, "  %s  %s\n", color.CyanString("你"), timestamp)
		for _, line := range strings.Split(msg.Content, "\n") {
			fmt.Fprintf(out, "  %s\n", line)
		}
	} else {
		fmt.Fprintf(out, "  %s  %s\n", color.GreenString("助手"), timestamp)
		for _, line := range strings.Split(msg.Content, "\n") {
			if strings.TrimSpace(line) == "" {
				fmt.Fprintf(out, "  %s\n", color.GreenString("┃"))
			} else {
				fmt.Fprintf(out, "  %s %s\n", color.GreenString("┃"), line)
			}
		}
	}
	fmt.Fprintln(out)
}
