package agent

import (
	"fmt"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewShowCmd 创建查看 Agent 详情的命令。
func NewShowCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "show <id>",
		Short: "查看 Agent 详情",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runAgentShow(f, args[0])
		},
	}
}

func runAgentShow(f *factory.Factory, agentID string) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载 Agent 详情...")
	var resp client.ApiResponse[client.AgentResponse]
	err = c.Get("/api/v1/agents/"+agentID, &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "Agent 不存在: %s", agentID)
			default:
				output.Error(out, "获取 Agent 详情失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	agent := resp.Data
	// 展示 Agent 详情
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("Agent ID:"), agent.ID)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("名称:"), agent.Name)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("系统提示词:"), truncatePrompt(agent.SystemPrompt, 80))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("模型提供商:"), agent.ModelProviderId)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("模型 ID:"), agent.ModelId)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("温度:"), formatFloat(agent.Temperature))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("最大轮次:"), formatInt(agent.MaxTurns))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("权限模式:"), agent.PermissionMode)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("记忆:"), formatBool(agent.MemoryEnabled))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("RAG:"), formatBool(agent.RagEnabled))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("网络:"), formatBool(agent.NetworkEnabled))
	fmt.Fprintf(out, "  %s %d\n", color.HiBlackString("配置版本:"), agent.ConfigVersion)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("创建时间:"), output.FormatTime(agent.CreatedAt))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("更新时间:"), output.FormatTime(agent.UpdatedAt))
	fmt.Fprintln(out)

	return nil
}

// truncatePrompt 截断过长的系统提示词用于显示。
func truncatePrompt(prompt string, maxLen int) string {
	if len(prompt) == 0 {
		return "(未设置)"
	}
	if len(prompt) > maxLen {
		return prompt[:maxLen] + "..."
	}
	return prompt
}

// formatFloat 格式化可选浮点数。
func formatFloat(v *float64) string {
	if v == nil {
		return "(默认)"
	}
	return fmt.Sprintf("%.2f", *v)
}

// formatInt 格式化可选整数。
func formatInt(v *int) string {
	if v == nil {
		return "(默认)"
	}
	return fmt.Sprintf("%d", *v)
}

// formatBool 格式化可选布尔值为中文状态。
func formatBool(v *bool) string {
	if v == nil {
		return "(默认)"
	}
	if *v {
		return "已启用"
	}
	return "已禁用"
}
