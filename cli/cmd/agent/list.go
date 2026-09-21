package agent

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewListCmd 创建 Agent 列表命令。
func NewListCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "list",
		Short: "列出所有 Agent",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runAgentList(f)
		},
	}
	return cmd
}

func runAgentList(f *factory.Factory) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载 Agent 列表...")
	var resp client.ApiResponse[[]client.AgentResponse]
	err = c.Get("/api/v1/agents", &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "获取 Agent 列表失败: %s", err)
		return err
	}

	agents := resp.Data
	if len(agents) == 0 {
		output.Info(out, "暂无 Agent")
		fmt.Fprintln(out)
		output.Hint(out, "使用 spaceagent agent create 创建你的第一个 Agent")
		return nil
	}

	table := output.NewTable(out, []string{"ID", "名称", "模型", "权限模式", "创建时间"})
	for _, a := range agents {
		table.Append([]string{
			output.TruncateID(a.ID),
			a.Name,
			a.ModelId,
			a.PermissionMode,
			output.FormatTime(a.CreatedAt),
		})
	}
	table.Render()
	return nil
}
