// status.go 实现了查看登录状态的命令。
//
// 用法: spaceagent auth status
//
// 显示当前的登录信息：服务器地址、用户名、角色、令牌有效期。
// 如果未登录，会提示用户先登录。
package auth

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewStatusCmd 创建状态查看命令。
func NewStatusCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "status",
		Short: "查看当前登录状态",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runStatus(f)
		},
	}
}

// runStatus 执行状态查看逻辑。
func runStatus(f *factory.Factory) error {
	out := f.IOStreams.Out

	cfg, err := f.Config()
	if err != nil {
		output.Error(out, "读取配置失败: %s", err)
		return err
	}

	// 检查是否已登录
	if !cfg.IsLoggedIn() {
		output.Warning(out, "当前未登录")
		output.Hint(out, "使用 spaceagent auth login 登录，或 spaceagent auth register 注册新账户")
		return nil
	}

	// 显示登录信息
	fmt.Fprintln(out)
	output.Success(out, "已登录")
	fmt.Fprintf(out, "  服务器:  %s\n", cfg.Server)
	fmt.Fprintf(out, "  用户名:  %s\n", cfg.Username)

	// 角色用不同颜色显示
	if cfg.IsAdmin() {
		fmt.Fprintf(out, "  角色:    %s\n", color.YellowString("ADMIN"))
	} else {
		fmt.Fprintf(out, "  角色:    %s\n", color.CyanString("USER"))
	}

	fmt.Fprintf(out, "  用户 ID: %s\n", cfg.UserID)
	if cfg.TenantID != "" {
		fmt.Fprintf(out, "  工作区:  %s\n", cfg.TenantID)
		fmt.Fprintf(out, "  工作区角色: %s\n", color.MagentaString(cfg.TenantRole))
	}

	// 解析 JWT 令牌中的过期时间
	// JWT 由三部分组成，用 . 分隔：header.payload.signature
	// payload 是 base64 编码的 JSON，包含 exp（过期时间戳）
	if expiry, err := parseJWTExpiry(cfg.Token); err == nil {
		remaining := time.Until(expiry)
		if remaining > 0 {
			fmt.Fprintf(out, "  令牌:    %s（剩余 %s）\n",
				color.GreenString("有效"),
				formatDuration(remaining))
		} else {
			fmt.Fprintf(out, "  令牌:    %s\n", color.RedString("已过期"))
			output.Hint(out, "使用 spaceagent auth login 重新登录")
		}
	}

	fmt.Fprintln(out)
	return nil
}

// parseJWTExpiry 从 JWT 令牌中解析过期时间。
// JWT 格式: header.payload.signature
// 我们只需要 payload 部分，不需要验证签名（那是服务器的事）。
func parseJWTExpiry(token string) (time.Time, error) {
	// 按 . 分割 JWT
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return time.Time{}, fmt.Errorf("无效的 JWT 格式")
	}

	// 解码 payload（第二部分）
	// JWT 使用 base64url 编码（不带 padding）
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return time.Time{}, err
	}

	// 解析 JSON，只提取 exp 字段
	var claims struct {
		Exp int64 `json:"exp"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return time.Time{}, err
	}

	// Unix 时间戳转换为 Go 的 time.Time
	return time.Unix(claims.Exp, 0), nil
}

// formatDuration 将时间间隔格式化为人类可读的字符串。
func formatDuration(d time.Duration) string {
	hours := int(d.Hours())
	if hours >= 24 {
		return fmt.Sprintf("%d 天 %d 小时", hours/24, hours%24)
	}
	if hours > 0 {
		return fmt.Sprintf("%d 小时 %d 分钟", hours, int(d.Minutes())%60)
	}
	return fmt.Sprintf("%d 分钟", int(d.Minutes()))
}
