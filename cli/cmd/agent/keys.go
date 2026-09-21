package agent

import (
	"fmt"
	"strings"

	"github.com/AlecAivazis/survey/v2"
	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewKeysCmd 创建 Agent 密钥管理子命令组。
func NewKeysCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "keys",
		Short: "管理 Agent API 密钥",
		Long:  "管理 Agent 的 API 密钥：列出、创建、撤销。",
	}

	cmd.AddCommand(newKeysListCmd(f))
	cmd.AddCommand(newKeysCreateCmd(f))
	cmd.AddCommand(newKeysRevokeCmd(f))

	return cmd
}

// newKeysListCmd 创建密钥列表命令。
func newKeysListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list <agentId>",
		Short: "列出 Agent 的所有 API 密钥",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKeysList(f, args[0])
		},
	}
}

func runKeysList(f *factory.Factory, agentID string) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载密钥列表...")
	var resp client.ApiResponse[[]client.AgentKeyResponse]
	err = c.Get(fmt.Sprintf("/api/v1/agents/%s/keys", agentID), &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "Agent 不存在: %s", agentID)
			default:
				output.Error(out, "获取密钥列表失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	keys := resp.Data
	if len(keys) == 0 {
		output.Info(out, "暂无 API 密钥")
		fmt.Fprintln(out)
		output.Hint(out, "使用 spaceagent agent keys create %s 创建密钥", agentID)
		return nil
	}

	table := output.NewTable(out, []string{"ID", "名称", "前缀", "作用域", "启用", "创建时间"})
	for _, k := range keys {
		enabled := "是"
		if !k.Enabled {
			enabled = "否"
		}
		table.Append([]string{
			output.TruncateID(k.ID),
			k.Name,
			k.KeyPrefix,
			strings.Join(k.Scopes, ", "),
			enabled,
			output.FormatTime(k.CreatedAt),
		})
	}
	table.Render()
	return nil
}

// newKeysCreateCmd 创建密钥创建命令。
func newKeysCreateCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "create <agentId>",
		Short: "为 Agent 创建 API 密钥",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKeysCreate(f, args[0])
		},
	}
}

func runKeysCreate(f *factory.Factory, agentID string) error {
	out := f.IOStreams.Out

	// 收集密钥名称
	var name string
	if err := survey.AskOne(&survey.Input{
		Message: "密钥名称（必填）:",
	}, &name, survey.WithValidator(survey.Required)); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集作用域
	var scope string
	if err := survey.AskOne(&survey.Select{
		Message: "选择作用域:",
		Options: []string{"CHAT", "ADMIN"},
		Default: "CHAT",
	}, &scope); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	req := client.AgentKeyCreateRequest{
		Name:   name,
		Scopes: []string{scope},
	}

	s := output.StartSpinner(" 创建密钥中...")
	var resp client.ApiResponse[client.AgentKeyCreateResponse]
	err = c.Post(fmt.Sprintf("/api/v1/agents/%s/keys", agentID), req, &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "Agent 不存在: %s", agentID)
			default:
				output.Error(out, "创建密钥失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	// 醒目显示原始密钥值
	output.Success(out, "密钥创建成功")
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  %s\n", color.YellowString("⚠️ 请保存此密钥，它只会显示一次"))
	fmt.Fprintln(out)
	data := resp.Data
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("密钥:"), color.YellowString(data.RawKey))
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("ID:"), data.ApiKey.ID)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("名称:"), data.ApiKey.Name)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("前缀:"), data.ApiKey.KeyPrefix)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("作用域:"), strings.Join(data.ApiKey.Scopes, ", "))
	fmt.Fprintln(out)

	return nil
}

// newKeysRevokeCmd 创建密钥撤销命令。
func newKeysRevokeCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "revoke <agentId> <keyId>",
		Short: "撤销 Agent 的 API 密钥",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKeysRevoke(f, args[0], args[1])
		},
	}
}

func runKeysRevoke(f *factory.Factory, agentID, keyID string) error {
	out := f.IOStreams.Out

	// 确认撤销
	var confirmed bool
	prompt := &survey.Confirm{
		Message: fmt.Sprintf("确定要撤销密钥 %s 吗？此操作不可撤销", keyID),
		Default: false,
	}
	if err := survey.AskOne(prompt, &confirmed); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	if !confirmed {
		output.Info(out, "操作已取消")
		return nil
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	// 后端返回 204 No Content
	err = c.Delete(fmt.Sprintf("/api/v1/agents/%s/keys/%s", agentID, keyID), nil)
	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "密钥不存在: %s", keyID)
			case 403:
				output.Error(out, "无权撤销此密钥")
			default:
				output.Error(out, "撤销失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	output.Success(out, "密钥已撤销: %s", keyID)
	return nil
}
