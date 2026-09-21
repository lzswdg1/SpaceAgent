// Package cmd 定义了 CLI 的所有命令。
//
// root.go 是命令树的根节点，负责：
// 1. 创建 Factory（依赖容器）
// 2. 注册所有子命令
// 3. 定义全局 flag（如 --server）
// 4. 显示 ASCII art 横幅
package cmd

import (
	"context"

	"github.com/spf13/cobra"

	modelprovider "github.com/lzswdg1/SpaceAgent/cli/cmd/admin"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/agent"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/auth"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/chat"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/kb"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/mcp"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/memory"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/project"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/tenant"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/tools"
	"github.com/lzswdg1/SpaceAgent/cli/cmd/user"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// banner 是程序启动时显示的 ASCII art 横幅。
// 使用 Go 的原始字符串字面量（反引号），可以包含换行和特殊字符。
const banner = `
  ╔══════════════════════════════════════╗
  ║   SpaceAgent CLI                    ║
  ╚══════════════════════════════════════╝`

// NewRootCmd 创建并返回根命令。
// 这是整个 CLI 的入口点，所有子命令都挂载在这个命令下。
type Application struct {
	Root          *cobra.Command
	factory       *factory.Factory
	outputRuntime *output.Runtime
	errorRendered bool
	jsonAlias     bool
}

func NewApplication() *Application {
	f := factory.New()
	app := &Application{
		factory:       f,
		outputRuntime: output.NewRuntime(f.IOStreams.Out, f.IOStreams.ErrOut),
	}
	app.Root = newRootCmd(app)
	return app
}

func NewRootCmd() *cobra.Command {
	return NewApplication().Root
}

func newRootCmd(app *Application) *cobra.Command {
	f := app.factory
	cobra.EnableTraverseRunHooks = true

	// cobra.Command 是 cobra 库的核心结构体
	// Use: 命令名称（用户在终端输入的名字）
	// Short: 简短描述（显示在父命令的帮助信息中）
	// Long: 详细描述（显示在自身的帮助信息中）
	rootCmd := &cobra.Command{
		Use:   "spaceagent",
		Short: "SpaceAgent CLI - AI Agent 平台命令行工具",
		Long:  banner + "\n\n  一个面向 AI Agent 平台的命令行工具。\n  支持交互式聊天、Agent 管理、知识库、工具和模型配置。",
		// SilenceUsage: 当命令执行出错时，不自动打印用法信息
		SilenceUsage: true,
		// SilenceErrors: 错误在 main.go 中统一处理
		SilenceErrors: true,
	}

	// Set version for --version flag
	rootCmd.Version = version
	rootCmd.PersistentFlags().StringVar(
		&f.Invocation.Profile,
		"profile",
		f.Invocation.Profile,
		"使用命名 Profile（也可设置 SPACEAGENT_PROFILE）",
	)
	rootCmd.PersistentFlags().StringVar(
		&f.Invocation.Format,
		"format",
		f.Invocation.Format,
		"输出格式: pretty|json（也可设置 SPACEAGENT_FORMAT）",
	)
	rootCmd.PersistentFlags().BoolVar(&app.jsonAlias, "json", false, "等价于 --format json")
	rootCmd.PersistentPreRunE = func(cmd *cobra.Command, args []string) error {
		if app.jsonAlias {
			f.Invocation.Format = output.FormatJSON
		}
		out, errOut, err := app.outputRuntime.Begin(
			f.Invocation.Format,
			cmd.CommandPath(),
			config.ResolveProfile(f.Invocation.Profile),
		)
		if err != nil {
			return err
		}
		f.IOStreams.Out = out
		f.IOStreams.ErrOut = errOut
		return nil
	}

	// 注册所有子命令
	rootCmd.AddCommand(auth.NewAuthCmd(f))
	rootCmd.AddCommand(chat.NewChatCmd(f))
	rootCmd.AddCommand(user.NewUserCmd(f))
	rootCmd.AddCommand(agent.NewAgentCmd(f))
	rootCmd.AddCommand(kb.NewKBCmd(f))
	rootCmd.AddCommand(memory.NewMemoryCmd(f))
	rootCmd.AddCommand(project.NewProjectCmd(f))
	rootCmd.AddCommand(tenant.NewTenantCmd(f))
	rootCmd.AddCommand(mcp.NewMCPCmd(f))
	rootCmd.AddCommand(tools.NewToolCmd(f))
	rootCmd.AddCommand(modelprovider.NewProviderCmd(f))
	rootCmd.AddCommand(NewHealthCmd(f))
	rootCmd.AddCommand(NewConfigCmd(f))
	rootCmd.AddCommand(NewVersionCmd(f))

	return rootCmd
}

func (a *Application) ExecuteContext(ctx context.Context) error {
	err := a.Root.ExecuteContext(ctx)
	if a.jsonAlias {
		a.factory.Invocation.Format = output.FormatJSON
	}
	a.errorRendered = a.outputRuntime.Finish(
		a.factory.Invocation.Format,
		a.Root.CommandPath(),
		config.ResolveProfile(a.factory.Invocation.Profile),
		err,
	)
	return err
}

func (a *Application) ErrorRendered() bool {
	return a.errorRendered
}
