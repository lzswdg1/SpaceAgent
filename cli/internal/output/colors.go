// Package output 提供终端颜色和样式相关的工具函数。
//
// 使用 fatih/color 库来处理 ANSI 颜色码。这个库的优点是：
// 1. 自动检测终端是否支持颜色（管道重定向时自动禁用颜色）
// 2. 跨平台兼容（Windows 也能正常显示）
// 3. API 简洁易用
//
// 颜色方案设计：
// - 用户消息：青色（Cyan），表示"我说的话"
// - 助手回复：绿色（Green），表示"温暖的回应"
// - 系统提示：黄色（Yellow），表示"需要注意"
// - 错误信息：红色（Red），表示"出了问题"
// - 次要信息：灰色（HiBlack），表示"补充说明"
package output

import "github.com/fatih/color"

// ==========================================
// 预定义的颜色样式
// ==========================================

// 这些变量使用 color.New() 创建，可以组合多种属性。
// color.Bold 表示加粗，color.FgXxx 表示前景色（文字颜色）。
var (
	// ColorUser 用于显示用户输入的消息（青色加粗）
	ColorUser = color.New(color.FgCyan, color.Bold)

	// ColorAssistant 用于显示助手的回复（绿色）
	ColorAssistant = color.New(color.FgGreen)

	// ColorSystem 用于显示系统提示信息（黄色）
	ColorSystem = color.New(color.FgYellow)

	// ColorError 用于显示错误信息（红色加粗）
	ColorError = color.New(color.FgRed, color.Bold)

	// ColorSuccess 用于显示成功信息（绿色加粗）
	ColorSuccess = color.New(color.FgGreen, color.Bold)

	// ColorMuted 用于显示次要信息（灰色）
	// HiBlack 在大多数终端中显示为灰色
	ColorMuted = color.New(color.FgHiBlack)

	// ColorBold 仅加粗，不改变颜色
	ColorBold = color.New(color.Bold)
)

// ==========================================
// 风险等级徽章
// ==========================================

// RiskBadge 根据风险等级返回带颜色的徽章字符串。
// 对应后端 SafetyAssessment 的三个级别：
// - LOW: 绿色，表示安全
// - MEDIUM: 黄色，表示需要关注
// - HIGH: 红色，表示高风险（消息被阻断）
func RiskBadge(level string) string {
	switch level {
	case "LOW":
		// color.GreenString 返回带绿色 ANSI 码的字符串
		return color.GreenString("[LOW]")
	case "MEDIUM":
		return color.YellowString("[MEDIUM]")
	case "HIGH":
		return color.RedString("[HIGH]")
	default:
		return color.HiBlackString("[%s]", level)
	}
}

// ==========================================
// 情绪标签
// ==========================================

// MoodTag 根据检测到的情绪返回带 emoji 的标签。
// 后端通过 AI 分析用户消息的情绪，返回如 "neutral"、"stressed" 等。
func MoodTag(mood string) string {
	if mood == "" {
		return ""
	}

	// emoji 映射表
	// Go 的 map 类似 Java 的 HashMap
	emojis := map[string]string{
		"neutral":  "😐",
		"happy":    "😊",
		"sad":      "😢",
		"anxious":  "😰",
		"stressed": "😫",
		"angry":    "😠",
		"hopeful":  "🌟",
		"grateful": "🙏",
	}

	emoji, ok := emojis[mood]
	if !ok {
		// 未知情绪使用默认 emoji
		emoji = "💭"
	}

	return color.CyanString("%s %s", emoji, mood)
}
