package agent

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewUpdateCmd 创建 Agent 更新命令。
func NewUpdateCmd(f *factory.Factory) *cobra.Command {
	var (
		name            string
		systemPrompt    string
		modelProviderID string
		modelID         string
		temperature     string
		maxTurns        int
		permissionMode  string
	)

	cmd := &cobra.Command{
		Use:   "update <id>",
		Short: "更新 Agent 配置",
		Long:  "更新指定 Agent 的配置。支持通过 flag 直接指定字段，或不带 flag 进入交互式模式逐项修改。",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runUpdate(f, cmd, args[0], name, systemPrompt, modelProviderID, modelID, temperature, maxTurns, permissionMode)
		},
	}

	cmd.Flags().StringVar(&name, "name", "", "Agent 名称")
	cmd.Flags().StringVar(&systemPrompt, "system-prompt", "", "系统提示词")
	cmd.Flags().StringVar(&modelProviderID, "provider", "", "模型提供商 ID")
	cmd.Flags().StringVar(&modelID, "model-id", "", "模型 ID")
	cmd.Flags().StringVar(&temperature, "temperature", "", "温度参数 (0.0-2.0)")
	cmd.Flags().IntVar(&maxTurns, "max-turns", 0, "最大轮次 (1-100)")
	cmd.Flags().StringVar(&permissionMode, "permission-mode", "", "权限模式 (auto/ask/deny)")

	return cmd
}

func runUpdate(f *factory.Factory, cmd *cobra.Command, agentID string, name, systemPrompt, modelProviderID, modelID, temperature string, maxTurns int, permissionMode string) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	// 判断是否有任何 flag 被显式设置
	flagsProvided := cmd.Flags().Changed("name") ||
		cmd.Flags().Changed("system-prompt") ||
		cmd.Flags().Changed("provider") ||
		cmd.Flags().Changed("model-id") ||
		cmd.Flags().Changed("temperature") ||
		cmd.Flags().Changed("max-turns") ||
		cmd.Flags().Changed("permission-mode")

	req := client.AgentUpdateRequest{}

	if flagsProvided {
		// 使用 flag 模式：仅设置已提供的字段
		if cmd.Flags().Changed("name") {
			req.Name = name
		}
		if cmd.Flags().Changed("system-prompt") {
			req.SystemPrompt = systemPrompt
		}
		if cmd.Flags().Changed("provider") {
			req.ModelProviderId = modelProviderID
		}
		if cmd.Flags().Changed("model-id") {
			req.ModelId = modelID
		}
		if cmd.Flags().Changed("temperature") {
			temp, err := strconv.ParseFloat(temperature, 64)
			if err != nil {
				output.Error(out, "temperature 参数格式错误: %s", err)
				return err
			}
			req.Temperature = &temp
		}
		if cmd.Flags().Changed("max-turns") {
			req.MaxTurns = &maxTurns
		}
		if cmd.Flags().Changed("permission-mode") {
			req.PermissionMode = permissionMode
		}
	} else {
		// 交互式模式
		var inputName, inputPrompt, inputModel, inputTemp, inputTurns, inputPerm string

		if err := survey.AskOne(&survey.Input{Message: "Agent 名称 (直接回车跳过):"}, &inputName); err != nil {
			output.Info(out, "操作已取消")
			return nil
		}
		if err := survey.AskOne(&survey.Input{Message: "系统提示词 (直接回车跳过):"}, &inputPrompt); err != nil {
			output.Info(out, "操作已取消")
			return nil
		}
		if err := survey.AskOne(&survey.Input{Message: "模型 ID (直接回车跳过):"}, &inputModel); err != nil {
			output.Info(out, "操作已取消")
			return nil
		}
		if err := survey.AskOne(&survey.Input{Message: "温度参数 (0.0-2.0，直接回车跳过):"}, &inputTemp); err != nil {
			output.Info(out, "操作已取消")
			return nil
		}
		if err := survey.AskOne(&survey.Input{Message: "最大轮次 (1-100，直接回车跳过):"}, &inputTurns); err != nil {
			output.Info(out, "操作已取消")
			return nil
		}
		if err := survey.AskOne(&survey.Input{Message: "权限模式 (auto/ask/deny，直接回车跳过):"}, &inputPerm); err != nil {
			output.Info(out, "操作已取消")
			return nil
		}

		// 仅将非空输入设置到请求中
		inputName = strings.TrimSpace(inputName)
		inputPrompt = strings.TrimSpace(inputPrompt)
		inputModel = strings.TrimSpace(inputModel)
		inputTemp = strings.TrimSpace(inputTemp)
		inputTurns = strings.TrimSpace(inputTurns)
		inputPerm = strings.TrimSpace(inputPerm)

		if inputName == "" && inputPrompt == "" && inputModel == "" && inputTemp == "" && inputTurns == "" && inputPerm == "" {
			output.Info(out, "未修改任何内容")
			return nil
		}

		if inputName != "" {
			req.Name = inputName
		}
		if inputPrompt != "" {
			req.SystemPrompt = inputPrompt
		}
		if inputModel != "" {
			req.ModelId = inputModel
		}
		if inputTemp != "" {
			temp, err := strconv.ParseFloat(inputTemp, 64)
			if err != nil {
				output.Error(out, "温度参数格式错误: %s", err)
				return err
			}
			req.Temperature = &temp
		}
		if inputTurns != "" {
			turns, err := strconv.Atoi(inputTurns)
			if err != nil {
				output.Error(out, "最大轮次格式错误: %s", err)
				return err
			}
			req.MaxTurns = &turns
		}
		if inputPerm != "" {
			req.PermissionMode = inputPerm
		}
	}

	// 发送更新请求
	s := output.StartSpinner(" 更新中...")
	var resp client.ApiResponse[client.AgentResponse]
	err = c.Put(fmt.Sprintf("/api/v1/agents/%s", agentID), req, &resp)
	output.StopSpinner(s)

	if err != nil {
		// 处理 404 错误
		if apiErr, ok := err.(*client.APIError); ok && apiErr.StatusCode == 404 {
			output.Error(out, "Agent 不存在: %s", agentID)
			return err
		}
		output.Error(out, "更新失败: %s", err)
		return err
	}

	output.Success(out, "Agent 已更新: %s", resp.Data.Name)
	return nil
}
