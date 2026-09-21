// register.go 实现了注册命令。
//
// 用法:
//
//	spaceagent auth register                       # 交互式注册（推荐）
//	spaceagent auth register --username xxx --password xxx  # 非交互式
//
// 注册成功后会自动登录（保存 JWT 令牌），无需再次执行 login。
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

// NewRegisterCmd 创建注册命令。
func NewRegisterCmd(f *factory.Factory) *cobra.Command {
	var username, password, displayName string

	cmd := &cobra.Command{
		Use:   "register",
		Short: "注册新账户",
		Long:  "创建一个新的 SpaceAgent 账户。\n注册成功后会自动登录。",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runRegister(f, username, password, displayName)
		},
	}

	cmd.Flags().StringVar(&username, "username", "", "用户名 (3-32 字符)")
	cmd.Flags().StringVar(&password, "password", "", "密码 (6-64 字符)")
	cmd.Flags().StringVar(&displayName, "display-name", "", "显示名称 (可选)")

	return cmd
}

// runRegister 执行注册逻辑。
func runRegister(f *factory.Factory, username, password, displayName string) error {
	out := f.IOStreams.Out

	// 交互式输入
	if username == "" {
		prompt := &survey.Input{Message: "用户名 (3-32 字符):"}
		if err := survey.AskOne(prompt, &username); err != nil {
			return fmt.Errorf("输入取消")
		}
	}
	if password == "" {
		prompt := &survey.Password{Message: "密码 (6-64 字符):"}
		if err := survey.AskOne(prompt, &password); err != nil {
			return fmt.Errorf("输入取消")
		}
		// 确认密码
		var confirmPassword string
		confirmPrompt := &survey.Password{Message: "确认密码:"}
		if err := survey.AskOne(confirmPrompt, &confirmPassword); err != nil {
			return fmt.Errorf("输入取消")
		}
		if password != confirmPassword {
			output.Error(out, "两次输入的密码不一致")
			return fmt.Errorf("密码不匹配")
		}
	}
	if displayName == "" {
		prompt := &survey.Input{
			Message: "显示名称 (可选，直接回车跳过):",
			Default: username,
		}
		if err := survey.AskOne(prompt, &displayName); err != nil {
			return fmt.Errorf("输入取消")
		}
	}

	// 发送注册请求
	s := output.StartSpinner(" 正在注册...")

	c, err := f.Client()
	if err != nil {
		output.StopSpinner(s)
		return err
	}

	var resp client.ApiResponse[client.AuthResponse]
	err = c.Post("/api/v1/auth/register", client.RegisterRequest{
		Username:    username,
		Password:    password,
		DisplayName: displayName,
	}, &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 409:
				output.ErrorWithHint(out, "用户名已被占用", "请换一个用户名重试")
			case 400:
				output.Error(out, "注册失败: %s", apiErr.Msg)
			default:
				output.Error(out, "注册失败: %s", apiErr.Msg)
			}
		} else {
			output.ErrorWithHint(out, "无法连接到服务器", "请先运行 spaceagent health 检查服务器状态")
		}
		return err
	}

	// 注册成功后自动保存登录信息
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

	f.ReloadConfig()

	output.Success(out, "注册成功！欢迎，%s", resp.Data.Username)
	output.Info(out, "角色: %s", resp.Data.Role)
	output.Info(out, "工作区角色: %s", resp.Data.TenantRole)
	output.Hint(out, "使用 spaceagent chat 开始对话")

	return nil
}
