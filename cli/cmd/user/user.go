// Package user 实现了用户信息和画像相关的命令。
//
// 命令：
//   - spaceagent user me          查看当前用户信息
//   - spaceagent user profile     查看用户画像
//   - spaceagent user update-profile  更新用户画像
package user

import (
	"fmt"
	"io"

	"github.com/AlecAivazis/survey/v2"
	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// UserResponse 对应后端 UserController 的响应
type UserResponse struct {
	UserID      string `json:"userId"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
	Role        string `json:"role"`
	CreatedAt   string `json:"createdAt"`
}

// UserProfileResponse 对应后端 ProfileController 的响应
type UserProfileResponse struct {
	UserID        string `json:"userId"`
	PreferredTone string `json:"preferredTone"`
	Timezone      string `json:"timezone"`
	Summary       string `json:"summary"`
	CreatedAt     string `json:"createdAt"`
	UpdatedAt     string `json:"updatedAt"`
}

// UpdateProfileRequest 对应后端 UpdateProfileRequest
type UpdateProfileRequest struct {
	PreferredTone string `json:"preferredTone,omitempty"`
	Timezone      string `json:"timezone,omitempty"`
	Summary       string `json:"summary,omitempty"`
}

// NewUserCmd 创建用户命令组。
func NewUserCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "user",
		Short: "用户信息和画像管理",
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

	cmd.AddCommand(newMeCmd(f))
	cmd.AddCommand(newProfileCmd(f))
	cmd.AddCommand(newUpdateProfileCmd(f))

	return cmd
}

func newMeCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "me",
		Short: "查看当前用户信息",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runMe(f)
		},
	}
}

func newProfileCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "profile",
		Short: "查看用户画像",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runProfile(f)
		},
	}
}

func newUpdateProfileCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "update-profile",
		Short: "更新用户画像",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runUpdateProfile(f)
		},
	}
}

func runMe(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载用户信息...")
	var resp client.ApiResponse[UserResponse]
	err = c.Get("/api/v1/users/me", &resp)
	output.StopSpinner(s)

	if err != nil {
		output.Error(out, "获取用户信息失败: %s", err)
		return err
	}

	d := resp.Data
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("用户 ID:"), d.UserID)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("用户名:"), d.Username)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("显示名:"), d.DisplayName)
	if d.Role == "ADMIN" {
		fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("角色:"), color.YellowString("ADMIN"))
	} else {
		fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("角色:"), color.CyanString("USER"))
	}
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("创建时间:"), output.FormatTime(d.CreatedAt))
	fmt.Fprintln(out)
	return nil
}

func runProfile(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载用户画像...")
	var resp client.ApiResponse[UserProfileResponse]
	err = c.Get("/api/v1/users/me/profile", &resp)
	output.StopSpinner(s)

	if err != nil {
		output.Error(out, "获取用户画像失败: %s", err)
		return err
	}

	renderProfile(out, resp.Data)
	return nil
}

func runUpdateProfile(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	var tone, tz, summary string
	if err := survey.AskOne(&survey.Input{Message: "语气偏好 (如 WARM/GENTLE，直接回车跳过):"}, &tone); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Input{Message: "时区 (如 Asia/Shanghai，直接回车跳过):"}, &tz); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Input{Message: "个人简介 (直接回车跳过):"}, &summary); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	if tone == "" && tz == "" && summary == "" {
		output.Info(out, "未修改任何内容")
		return nil
	}

	s := output.StartSpinner(" 更新中...")
	req := UpdateProfileRequest{
		PreferredTone: tone,
		Timezone:      tz,
		Summary:       summary,
	}
	var resp client.ApiResponse[UserProfileResponse]
	err = c.Put("/api/v1/users/me/profile", req, &resp)
	output.StopSpinner(s)

	if err != nil {
		output.Error(out, "更新失败: %s", err)
		return err
	}

	output.Success(out, "画像已更新")
	renderProfile(out, resp.Data)
	return nil
}

func renderProfile(out io.Writer, d UserProfileResponse) {
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("语气偏好:"), d.PreferredTone)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("时区:"), d.Timezone)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("个人简介:"), d.Summary)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("更新时间:"), output.FormatTime(d.UpdatedAt))
	fmt.Fprintln(out)
}
