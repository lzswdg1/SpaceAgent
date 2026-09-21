// login.go 实现了登录命令。
//
// 用法:
//
//	spaceagent auth login                          # 交互式登录（推荐）
//	spaceagent auth login --username xxx --password xxx  # 非交互式（用于脚本/CI）
//
// 登录成功后，JWT 令牌会保存到当前解析的 ~/.spaceagent Profile，
// 后续命令会自动使用该令牌进行身份验证。
package auth

import (
	"fmt"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewLoginCmd 创建登录命令。
func NewLoginCmd(f *factory.Factory) *cobra.Command {
	// 用于接收 flag 值的变量
	var username, password string

	cmd := &cobra.Command{
		Use:   "login",
		Short: "登录到 SpaceAgent",
		Long:  "使用用户名和密码登录。\n支持交互式输入（推荐）和命令行参数两种方式。",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runLogin(f, username, password)
		},
	}

	// 定义可选的 flag
	// 如果不提供这些 flag，会进入交互式输入模式
	cmd.Flags().StringVar(&username, "username", "", "用户名")
	cmd.Flags().StringVar(&password, "password", "", "密码")

	return cmd
}

// runLogin 执行登录逻辑。
func runLogin(f *factory.Factory, username, password string) error {
	out := f.IOStreams.Out

	// 如果没有通过 flag 提供用户名和密码，使用交互式提示
	if username == "" {
		prompt := &survey.Input{Message: "用户名:"}
		if err := survey.AskOne(prompt, &username); err != nil {
			return fmt.Errorf("输入取消")
		}
	}
	if password == "" {
		// survey.Password 会隐藏输入的字符（显示为 *）
		prompt := &survey.Password{Message: "密码:"}
		if err := survey.AskOne(prompt, &password); err != nil {
			return fmt.Errorf("输入取消")
		}
	}

	// 显示加载动画
	s := output.StartSpinner(" 正在登录...")

	// 获取 HTTP 客户端（此时可能还没有 token）
	c, err := f.Client()
	if err != nil {
		output.StopSpinner(s)
		return err
	}

	// 发送登录请求
	var resp client.ApiResponse[client.AuthResponse]
	err = c.Post("/api/v1/auth/login", client.LoginRequest{
		Username: username,
		Password: password,
	}, &resp)
	output.StopSpinner(s)

	if err != nil {
		// 类型断言：检查是否是 API 错误
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 401:
				output.ErrorWithHint(out, "用户名或密码错误", "请检查后重试，或使用 spaceagent auth register 注册新账户")
			case 429:
				output.ErrorWithHint(out, "请求过于频繁", "请稍后再试")
			default:
				output.Error(out, "登录失败: %s", apiErr.Msg)
			}
		} else {
			output.ErrorWithHint(out, "无法连接到服务器", "请先运行 spaceagent health 检查服务器状态")
		}
		return err
	}

	// 保存登录信息到配置文件
	cfg, _ := f.Config()
	cfg.Token = resp.Data.Token
	cfg.RefreshToken = resp.Data.RefreshToken
	cfg.UserID = resp.Data.UserID
	cfg.Username = resp.Data.Username
	cfg.Role = resp.Data.Role
	cfg.TenantID = resp.Data.TenantID
	cfg.TenantRole = resp.Data.TenantRole
	if err := config.Save(cfg); err != nil {
		output.Error(out, "保存登录信息失败: %s", err)
		return err
	}

	// 重新加载配置缓存
	f.ReloadConfig()

	// 显示成功信息
	output.Success(out, "登录成功！欢迎回来，%s", resp.Data.Username)
	output.Info(out, "角色: %s", resp.Data.Role)
	output.Info(out, "工作区角色: %s", resp.Data.TenantRole)
	output.Hint(out, "使用 spaceagent chat 开始对话")

	return nil
}
