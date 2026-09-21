// Package agent 实现了 Agent 智能体管理相关的命令。
//
// 命令：
//   - spaceagent agent list        列出所有 Agent
//   - spaceagent agent show <id>   查看 Agent 详情
//   - spaceagent agent create      创建 Agent（交互式）
//   - spaceagent agent update <id> 更新 Agent 配置
//   - spaceagent agent delete <id> 删除 Agent
//   - spaceagent agent chat <id>   与 Agent 对话
//   - spaceagent agent keys        管理 Agent 密钥
package agent

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewAgentCmd 创建 Agent 命令组。
func NewAgentCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "agent",
		Short: "Agent 智能体管理",
		Long:  "管理 Agent 智能体：创建、配置、对话和 API Key。",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				output.ErrorWithHint(f.IOStreams.ErrOut, "请先登录", "使用 spaceagent auth login 登录")
				return fmt.Errorf("未登录")
			}
			return nil
		},
	}

	cmd.AddCommand(NewListCmd(f))
	cmd.AddCommand(NewShowCmd(f))
	cmd.AddCommand(NewCreateCmd(f))
	cmd.AddCommand(NewUpdateCmd(f))
	cmd.AddCommand(NewAgentChatCmd(f))
	cmd.AddCommand(NewDeleteCmd(f))
	cmd.AddCommand(NewKeysCmd(f))

	return cmd
}
