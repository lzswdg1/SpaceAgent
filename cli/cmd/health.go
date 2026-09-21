// health.go 实现了健康检查命令。
//
// 用法: spaceagent health
//
// 这是最简单的命令，不需要登录就能使用。
// 用于验证后端服务器是否正常运行，以及网络连接是否通畅。
// 建议在登录前先运行此命令确认服务器可用。
package cmd

import (
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewHealthCmd 创建健康检查命令。
func NewHealthCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "health",
		Short: "检查后端服务器健康状态",
		Long:  "向后端发送健康检查请求，验证服务器是否正常运行。\n不需要登录即可使用。",
		// RunE 是命令的执行函数，返回 error 表示执行失败。
		// cobra 中 RunE 比 Run 更推荐，因为可以优雅地处理错误。
		RunE: func(cmd *cobra.Command, args []string) error {
			return runHealth(f)
		},
	}
}

// runHealth 执行健康检查的业务逻辑。
// 将业务逻辑从命令定义中分离出来，是 cobra 的最佳实践。
func runHealth(f *factory.Factory) error {
	out := f.IOStreams.Out

	// 显示加载动画
	s := output.StartSpinner(" 正在检查服务器状态...")

	// 获取 HTTP 客户端
	c, err := f.Client()
	if err != nil {
		output.StopSpinner(s)
		output.Error(out, "无法创建客户端: %s", err)
		return err
	}

	// 发送健康检查请求
	// ApiResponse[HealthResponse] 是泛型类型，表示 data 字段是 HealthResponse 类型
	var resp client.ApiResponse[client.HealthResponse]
	err = c.Get("/api/v1/system/health", &resp)
	output.StopSpinner(s)

	if err != nil {
		output.ErrorWithHint(out,
			"无法连接到服务器",
			"请确认服务器已启动，或使用 spaceagent config set-server 设置正确的服务器地址")
		return err
	}

	// 根据状态显示不同颜色
	if resp.Data.Status == "UP" {
		output.Success(out, "服务器运行正常")
		output.Info(out, "服务: %s  状态: %s", resp.Data.Service, resp.Data.Status)
	} else {
		output.Warning(out, "服务器状态异常: %s", resp.Data.Status)
	}

	// 显示服务器地址
	cfg, _ := f.Config()
	output.Hint(out, "服务器地址: %s", cfg.Server)

	return nil
}
