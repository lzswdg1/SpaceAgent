// list.go 实现了记忆列表命令。
//
// 用法: spaceagent memory list
package memory

import (
	"fmt"
	"strings"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewListCmd 创建记忆列表命令。
func NewListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "查看记忆条目",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runMemoryList(f)
		},
	}
}

func runMemoryList(f *factory.Factory) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载记忆条目...")
	var resp client.ApiResponse[[]client.MemoryEntryResponse]
	err = c.Get("/api/v1/memory", &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "获取记忆列表失败: %s", apiErr.Msg)
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	entries := resp.Data
	if len(entries) == 0 {
		output.Info(out, "暂无记忆条目")
		return nil
	}

	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s %d 条记忆\n", color.HiBlackString("共"), len(entries))
	fmt.Fprintln(out)
	fmt.Fprintln(out, color.HiBlackString("  ─────────────────────────────────────"))
	fmt.Fprintln(out)

	for _, entry := range entries {
		timestamp := color.HiBlackString(output.FormatTime(entry.CreatedAt))
		fmt.Fprintf(out, "  %s  %s\n", color.CyanString("记忆"), timestamp)
		for _, line := range strings.Split(entry.Content, "\n") {
			fmt.Fprintf(out, "  %s\n", line)
		}
		fmt.Fprintln(out)
	}

	return nil
}
