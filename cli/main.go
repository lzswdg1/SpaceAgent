// SpaceAgent CLI 的入口文件。
//
// Go 程序的执行从 main 包的 main 函数开始，类似 Java 的 public static void main。
// 这个文件尽量保持简洁，只负责创建根命令并执行。
// 所有业务逻辑都在 cmd/ 和 internal/ 包中。
//
// 错误处理策略：
// 由于 root command 设置了 SilenceErrors: true，cobra 不会自动打印错误。
// 我们在这里统一处理：如果命令返回了错误但没有被命令自身打印，
// 则在这里输出，确保用户总能看到错误信息。
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/fatih/color"
	"github.com/lzswdg1/SpaceAgent/cli/cmd"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func main() {
	app := cmd.NewApplication()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := app.ExecuteContext(ctx); err != nil {
		if errors.Is(err, context.Canceled) {
			return
		}
		// 如果错误消息不是空的且不是已经被命令处理过的常见消息，
		// 打印一个兜底的错误提示，确保用户不会看到静默失败
		errMsg := err.Error()
		if !app.ErrorRendered() && errMsg != "" && errMsg != "未登录" && errMsg != "权限不足" && errMsg != "密码不匹配" {
			fmt.Fprintf(os.Stderr, "%s %s\n", color.RedString("✗"), errMsg)
		}
		os.Exit(output.ExitCode(err))
	}
}
