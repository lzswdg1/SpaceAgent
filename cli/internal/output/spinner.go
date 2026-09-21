// Package output 提供加载动画（spinner）功能。
//
// 在等待 API 响应时显示旋转动画，给用户视觉反馈，
// 表示程序正在工作而不是卡住了。
//
// 使用 briandowns/spinner 库，它提供了多种动画样式。
// 我们选择样式 14（点状动画：⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏），
// 这是现代 CLI 工具中最常见的加载动画。
package output

import (
	"time"

	"github.com/briandowns/spinner"
)

// StartSpinner 创建并启动一个加载动画。
// suffix: 动画旁边显示的文字，如 " 思考中..."
//
// 返回 *spinner.Spinner 对象，调用者需要在操作完成后调用 StopSpinner 停止动画。
//
// 使用示例：
//
//	s := output.StartSpinner(" 正在登录...")
//	// ... 执行耗时操作 ...
//	output.StopSpinner(s)
func StartSpinner(suffix string) *spinner.Spinner {
	if IsStructured() {
		return nil
	}
	// spinner.New 创建一个新的 spinner
	// 参数说明：
	// - spinner.CharSets[14]: 使用第 14 种动画样式（点状动画）
	// - 100 * time.Millisecond: 每 100 毫秒切换一帧
	s := spinner.New(spinner.CharSets[14], 100*time.Millisecond)

	// 设置动画旁边的文字
	s.Suffix = suffix

	// 启动动画（在后台 goroutine 中运行）
	// goroutine 是 Go 的轻量级线程，类似 Java 的虚拟线程
	s.Start()

	return s
}

// StopSpinner 停止加载动画并清除终端中的动画字符。
// 如果传入 nil，不做任何操作（防御性编程）。
func StopSpinner(s *spinner.Spinner) {
	if s != nil {
		s.Stop()
	}
}
