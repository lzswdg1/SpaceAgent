// Package memory 实现了记忆管理相关的命令。
//
// 命令：
//   - spaceagent memory list    查看记忆条目
//   - spaceagent memory clear   清除记忆
package memory

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewMemoryCmd 创建记忆管理命令组。
func NewMemoryCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "memory",
		Short: "记忆管理",
		Long:  "查看和清除 AI 从对话中学到的记忆条目。",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			out := f.IOStreams.Out
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				output.ErrorWithHint(out, "请先登录", "使用 spaceagent auth login 登录")
				return fmt.Errorf("未登录")
			}
			return nil
		},
	}

	cmd.AddCommand(NewListCmd(f))
	cmd.AddCommand(NewClearCmd(f))

	return cmd
}
