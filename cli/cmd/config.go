// config.go 实现了配置管理命令。
//
// 用法: spaceagent config set-server [--url URL]
//
// 用于设置后端网关的地址。默认地址是 http://127.0.0.1:8087。
// 如果后端部署在其他地址（如远程服务器），需要先用此命令配置。
package cmd

import (
	"fmt"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewConfigCmd 创建配置管理的父命令。
func NewConfigCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "config",
		Short: "管理 CLI 配置",
	}

	// 添加 set-server 子命令
	cmd.AddCommand(newSetServerCmd(f))
	cmd.AddCommand(newProfileCmd(f))

	return cmd
}

// newSetServerCmd 创建设置服务器地址的命令。
func newSetServerCmd(f *factory.Factory) *cobra.Command {
	// 用于接收 --url flag 的值
	var serverURL string

	cmd := &cobra.Command{
		Use:   "set-server",
		Short: "设置后端服务器地址",
		Long:  "设置 SpaceAgent 后端网关地址。\n默认地址: " + config.DefaultServer,
		RunE: func(cmd *cobra.Command, args []string) error {
			return runSetServer(f, serverURL)
		},
	}

	// 定义 --url flag
	// StringVarP 的参数：变量指针、flag 全名、flag 缩写、默认值、描述
	cmd.Flags().StringVarP(&serverURL, "url", "u", "", "服务器地址 (如 "+config.DefaultServer+")")

	return cmd
}

// runSetServer 执行设置服务器地址的逻辑。
func runSetServer(f *factory.Factory, serverURL string) error {
	out := f.IOStreams.Out

	// 如果没有通过 flag 指定 URL，使用交互式提示
	if serverURL == "" {
		// survey.AskOne 显示一个交互式输入提示
		// &survey.Input{} 是输入类型的提示（还有 Select、Confirm 等类型）
		prompt := &survey.Input{
			Message: "请输入服务器地址:",
			Default: config.DefaultServer,
		}
		if err := survey.AskOne(prompt, &serverURL); err != nil {
			return fmt.Errorf("输入取消: %w", err)
		}
	}

	// 加载现有配置
	cfg, err := f.Config()
	if err != nil {
		return err
	}

	// 更新服务器地址
	cfg.Server = serverURL

	// 保存配置
	if err := config.Save(cfg); err != nil {
		output.Error(out, "保存配置失败: %s", err)
		return err
	}

	output.Success(out, "服务器地址已设置为: %s", serverURL)
	output.Hint(out, "使用 spaceagent health 验证连接")

	// 重新加载配置缓存
	f.ReloadConfig()

	return nil
}
