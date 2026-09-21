package tools

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewToolCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "tool",
		Short: "查看 Agent 可绑定的 Tool / Skill",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				output.ErrorWithHint(f.IOStreams.ErrOut, "请先登录", "使用 spaceagent auth login 登录")
				return fmt.Errorf("未登录")
			}
			return nil
		},
	}
	cmd.AddCommand(NewToolListCmd(f))
	return cmd
}

func NewToolListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "列出内置工具、Skill 与 MCP 工具",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runToolList(f)
		},
	}
}

func runToolList(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	var resp client.ApiResponse[[]client.ToolDescriptorResponse]
	if err := c.Get("/api/v1/tools/catalog", &resp); err != nil {
		output.Error(out, "获取工具目录失败: %s", err)
		return err
	}
	if len(resp.Data) == 0 {
		output.Info(out, "当前没有可用工具")
		return nil
	}

	table := output.NewTable(out, []string{"ID", "名称", "分类", "默认", "可配置", "说明"})
	for _, tool := range resp.Data {
		table.Append([]string{
			tool.ID,
			tool.Name,
			tool.Category,
			boolLabel(tool.EnabledByDefault),
			boolLabel(tool.Configurable),
			output.TruncateString(tool.Description, 56),
		})
	}
	table.Render()
	fmt.Fprintf(out, "  共 %d 个可用工具\n\n", len(resp.Data))
	return nil
}

func boolLabel(value bool) string {
	if value {
		return "是"
	}
	return "否"
}
