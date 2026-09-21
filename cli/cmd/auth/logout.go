// logout.go 实现了登出命令。
//
// 用法: spaceagent auth logout
//
// 清除本地保存的 JWT 令牌和用户信息。
// 登出后需要重新登录才能使用需要认证的命令。
package auth

import (
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewLogoutCmd 创建登出命令。
func NewLogoutCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "logout",
		Short: "登出当前账户",
		Long:  "清除本地保存的登录信息。\n登出后需要重新登录才能使用聊天等功能。",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runLogout(f)
		},
	}
}

// runLogout 执行登出逻辑。
func runLogout(f *factory.Factory) error {
	out := f.IOStreams.Out

	// 检查是否已登录
	cfg, _ := f.Config()
	if !cfg.IsLoggedIn() {
		output.Info(out, "当前未登录，无需登出")
		return nil
	}

	username := cfg.Username
	if c, err := f.Client(); err == nil {
		var response client.ApiResponse[interface{}]
		if err := c.Post("/api/v1/auth/logout", client.RefreshTokenRequest{
			RefreshToken: cfg.RefreshToken,
		}, &response); err != nil {
			output.Warning(out, "服务端会话撤销失败，本地登录信息仍将清除")
		}
	}

	// 清除认证信息（保留服务器地址）
	if err := config.ClearAuth(); err != nil {
		output.Error(out, "登出失败: %s", err)
		return err
	}

	// 重新加载配置缓存
	f.ReloadConfig()

	output.Success(out, "已登出，再见 %s", username)
	output.Hint(out, "使用 spaceagent auth login 重新登录")

	return nil
}
