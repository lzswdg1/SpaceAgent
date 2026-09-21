// password.go 实现了修改密码命令。
//
// 用法: spaceagent auth change-password
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

// NewChangePasswordCmd 创建修改密码命令。
func NewChangePasswordCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "change-password",
		Short: "修改密码",
		Long:  "修改当前账户的密码。\n修改成功后需要重新登录。",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runChangePassword(f)
		},
	}
}

// runChangePassword 执行修改密码逻辑。
func runChangePassword(f *factory.Factory) error {
	out := f.IOStreams.Out

	cfg, err := f.Config()
	if err != nil || !cfg.IsLoggedIn() {
		output.ErrorWithHint(out, "请先登录", "使用 spaceagent auth login 登录")
		return fmt.Errorf("未登录")
	}

	var oldPassword string
	oldPrompt := &survey.Password{Message: "当前密码:"}
	if err := survey.AskOne(oldPrompt, &oldPassword); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	var newPassword string
	newPrompt := &survey.Password{Message: "新密码 (6-64 字符):"}
	if err := survey.AskOne(newPrompt, &newPassword); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	var confirmPassword string
	confirmPrompt := &survey.Password{Message: "确认新密码:"}
	if err := survey.AskOne(confirmPrompt, &confirmPassword); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	if newPassword != confirmPassword {
		output.Error(out, "两次输入的新密码不一致")
		return fmt.Errorf("密码不匹配")
	}

	s := output.StartSpinner(" 正在修改密码...")

	c, err := f.Client()
	if err != nil {
		output.StopSpinner(s)
		return err
	}

	var resp client.ApiResponse[interface{}]
	err = c.Post("/api/v1/auth/change-password", client.ChangePasswordRequest{
		OldPassword: oldPassword,
		NewPassword: newPassword,
	}, &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "修改密码失败: %s", apiErr.Msg)
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	_ = config.ClearAuth()
	f.ReloadConfig()

	output.Success(out, "密码修改成功")
	output.Hint(out, "请使用新密码重新登录: spaceagent auth login")

	return nil
}
