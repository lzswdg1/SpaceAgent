// Package cmd 定义了 CLI 的所有命令。
//
// helpers.go 提供公共辅助函数，供各子命令包（cmd/agent、cmd/kb、cmd/memory 等）复用。
package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// LoginGuard 返回一个 cobra PersistentPreRunE 处理函数，用于检查用户登录状态。
// 所有需要登录才能使用的命令组（agent、kb、memory、gateway、file）应使用此函数。
//
// 使用示例：
//
//	cmd := &cobra.Command{
//	    Use:               "agent",
//	    PersistentPreRunE: cmd.LoginGuard(f),
//	}
func LoginGuard(f *factory.Factory) func(cmd *cobra.Command, args []string) error {
	return func(cmd *cobra.Command, args []string) error {
		cfg, err := f.Config()
		if err != nil || !cfg.IsLoggedIn() {
			output.ErrorWithHint(f.IOStreams.ErrOut, "请先登录", "使用 spaceagent auth login 登录")
			return fmt.Errorf("未登录")
		}
		return nil
	}
}
