// Package output 提供通用的终端输出辅助函数。
//
// 这些函数为不同类型的消息提供统一的格式：
// - 成功消息：绿色 ✓ 前缀
// - 错误消息：红色 ✗ 前缀
// - 警告消息：黄色 ⚠ 前缀
// - 信息消息：蓝色 ℹ 前缀
// - 提示消息：灰色，用于引导用户下一步操作
//
// 所有函数都接受 io.Writer 参数，而不是直接写入 os.Stdout。
// 这样做是为了配合 Factory 模式中的 IOStreams，方便测试。
package output

import (
	"fmt"
	"io"

	"github.com/fatih/color"
)

// Success 输出成功消息，带绿色 ✓ 前缀。
// 用于操作成功后的确认信息，如 "✓ 登录成功"。
func Success(out io.Writer, format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	fmt.Fprintf(out, "%s %s\n", color.GreenString("✓"), msg)
}

// Error 输出错误消息，带红色 ✗ 前缀。
// 用于操作失败时的错误提示，如 "✗ 用户名或密码错误"。
func Error(out io.Writer, format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	fmt.Fprintf(out, "%s %s\n", color.RedString("✗"), msg)
}

// Warning 输出警告消息，带黄色 ⚠ 前缀。
// 用于需要用户注意但不影响操作的信息。
func Warning(out io.Writer, format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	fmt.Fprintf(out, "%s %s\n", color.YellowString("⚠"), msg)
}

// Info 输出信息消息，带蓝色 ℹ 前缀。
// 用于一般性的提示信息。
func Info(out io.Writer, format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	fmt.Fprintf(out, "%s %s\n", color.BlueString("ℹ"), msg)
}

// Hint 输出提示消息，灰色显示。
// 用于引导用户下一步操作，如 "提示: 使用 spaceagent chat 开始对话"。
// 参考了飞书 CLI 的 tips 设计。
func Hint(out io.Writer, format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	fmt.Fprintf(out, "  %s\n", color.HiBlackString(msg))
}

// ErrorWithHint 输出错误消息并附带操作提示。
// 这是飞书 CLI 的常见模式：告诉用户出了什么问题，以及如何解决。
func ErrorWithHint(out io.Writer, errMsg string, hint string) {
	Error(out, "%s", errMsg)
	Hint(out, "提示: %s", hint)
}

// FormatTime 格式化 ISO 8601 时间字符串为更易读的格式。
// 输入: "2026-05-02T16:38:29.043596Z" → 输出: "2026-05-02 16:38:29"
func FormatTime(isoTime string) string {
	if len(isoTime) >= 19 {
		return isoTime[:10] + " " + isoTime[11:19]
	}
	return isoTime
}

// FormatAPIError 将 API 错误格式化为用户友好的消息。
// 根据 HTTP 状态码提供不同的提示信息。
func FormatAPIError(out io.Writer, err error) {
	// 使用 Go 的类型断言（type assertion）检查错误类型
	// 类似 Java 的 instanceof
	// 这里导入 client 包会造成循环依赖，所以直接用 error 接口
	Error(out, "%s", err.Error())
}
