// Package auth 实现了认证相关的命令：登录、注册、状态查看、登出。
//
// auth.go 是 "spaceagent auth" 的父命令，本身不执行任何操作，
// 只是将子命令组织在一起。这是 cobra 中组织命令层级的标准方式。
package auth

import (
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
)

// NewAuthCmd 创建认证命令组。
// 用法:
//
//	spaceagent auth login      # 登录
//	spaceagent auth register   # 注册
//	spaceagent auth status     # 查看登录状态
//	spaceagent auth logout     # 登出
func NewAuthCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "auth",
		Short: "认证相关命令（登录、注册、登出）",
		Long:  "管理你的 SpaceAgent 账户认证。\n首次使用请先注册，然后登录。",
	}

	// 注册子命令
	cmd.AddCommand(NewLoginCmd(f))
	cmd.AddCommand(NewRegisterCmd(f))
	cmd.AddCommand(NewStatusCmd(f))
	cmd.AddCommand(NewRefreshCmd(f))
	cmd.AddCommand(NewChangePasswordCmd(f))
	cmd.AddCommand(NewLogoutCmd(f))

	return cmd
}
