package auth

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewRefreshCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "refresh",
		Short: "轮换访问令牌",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runRefresh(f)
		},
	}
}

func runRefresh(f *factory.Factory) error {
	out := f.IOStreams.Out
	cfg, err := f.Config()
	if err != nil || cfg.RefreshToken == "" {
		output.ErrorWithHint(out, "没有可用的刷新令牌", "请使用 spaceagent auth login 登录")
		return fmt.Errorf("刷新令牌不存在")
	}
	c, err := f.Client()
	if err != nil {
		return err
	}
	var response client.ApiResponse[client.AuthResponse]
	if err := c.Post("/api/v1/auth/refresh", client.RefreshTokenRequest{
		RefreshToken: cfg.RefreshToken,
	}, &response); err != nil {
		output.ErrorWithHint(out, "刷新登录状态失败", "刷新令牌可能已过期，请重新登录")
		return err
	}

	cfg.Token = response.Data.Token
	cfg.RefreshToken = response.Data.RefreshToken
	cfg.UserID = response.Data.UserID
	cfg.Username = response.Data.Username
	cfg.Role = response.Data.Role
	cfg.TenantID = response.Data.TenantID
	cfg.TenantRole = response.Data.TenantRole
	if err := config.Save(cfg); err != nil {
		return err
	}
	f.ReloadConfig()
	output.Success(out, "登录状态已刷新")
	return nil
}
