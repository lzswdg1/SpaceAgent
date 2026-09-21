package agent

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/AlecAivazis/survey/v2"
	"github.com/fatih/color"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewCreateCmd 创建 Agent 创建命令（交互式）。
func NewCreateCmd(f *factory.Factory) *cobra.Command {
	var opts createOptions
	cmd := &cobra.Command{
		Use:   "create",
		Short: "创建 Agent",
		Long:  "创建 Agent。可通过 flags 非交互创建；不传 --name 时进入交互式创建。",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runCreate(f, opts)
		},
	}
	cmd.Flags().StringVar(&opts.name, "name", "", "Agent 名称")
	cmd.Flags().StringVar(&opts.systemPrompt, "system-prompt", "", "系统提示词")
	cmd.Flags().StringVar(&opts.modelID, "model", "", "模型 ID")
	cmd.Flags().StringVar(&opts.modelProviderID, "provider", "", "模型提供商 ID")
	cmd.Flags().Float64Var(&opts.temperature, "temperature", 0.7, "温度参数 0.0-2.0")
	cmd.Flags().IntVar(&opts.maxTurns, "max-turns", 25, "最大轮次 1-100")
	cmd.Flags().StringVar(&opts.permissionMode, "permission", "auto", "权限模式 private/auto/ask/deny")
	cmd.Flags().BoolVar(&opts.memoryEnabled, "memory", true, "启用记忆")
	cmd.Flags().BoolVar(&opts.ragEnabled, "rag", false, "启用 RAG")
	cmd.Flags().StringSliceVar(&opts.knowledgeBaseIDs, "knowledge", nil, "关联知识库文档 ID，可逗号分隔或重复传入")
	cmd.Flags().StringSliceVar(&opts.enabledToolIDs, "tool", nil, "启用工具 ID，可逗号分隔或重复传入")
	return cmd
}

type createOptions struct {
	name             string
	systemPrompt     string
	modelID          string
	modelProviderID  string
	temperature      float64
	maxTurns         int
	permissionMode   string
	memoryEnabled    bool
	ragEnabled       bool
	knowledgeBaseIDs []string
	enabledToolIDs   []string
}

func runCreate(f *factory.Factory, opts createOptions) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	if opts.name != "" {
		return createAgentWithOptions(out, c, opts)
	}

	// 收集 Agent 名称（必填）
	var name string
	if err := survey.AskOne(&survey.Input{
		Message: "Agent 名称（必填）:",
	}, &name, survey.WithValidator(survey.Required)); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集系统提示词
	var systemPrompt string
	if err := survey.AskOne(&survey.Input{
		Message: "系统提示词（定义 Agent 的行为和角色）:",
	}, &systemPrompt); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集模型 ID
	var modelId string
	if err := survey.AskOne(&survey.Input{
		Message: "模型 ID（如 claude-sonnet-4-20250514，留空用默认）:",
	}, &modelId); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集温度参数
	var temperatureStr string
	if err := survey.AskOne(&survey.Input{
		Message: "温度参数（0.0-2.0）:",
		Default: "0.7",
	}, &temperatureStr); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集最大轮次
	var maxTurnsStr string
	if err := survey.AskOne(&survey.Input{
		Message: "最大轮次（1-100）:",
		Default: "25",
	}, &maxTurnsStr); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集权限模式
	var permissionMode string
	if err := survey.AskOne(&survey.Select{
		Message: "权限模式:",
		Options: []string{"auto", "ask", "deny"},
		Default: "auto",
	}, &permissionMode); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 收集是否启用记忆
	var memoryEnabled bool
	if err := survey.AskOne(&survey.Confirm{
		Message: "是否启用记忆:",
		Default: true,
	}, &memoryEnabled); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	// 解析温度参数
	temperature, err := strconv.ParseFloat(temperatureStr, 64)
	if err != nil {
		output.Error(out, "温度参数格式错误: %s", temperatureStr)
		return fmt.Errorf("无效的温度参数: %s", temperatureStr)
	}

	// 解析最大轮次
	maxTurns, err := strconv.Atoi(maxTurnsStr)
	if err != nil {
		output.Error(out, "最大轮次格式错误: %s", maxTurnsStr)
		return fmt.Errorf("无效的最大轮次: %s", maxTurnsStr)
	}

	// 构建请求
	req := client.AgentCreateRequest{
		Name:            name,
		SystemPrompt:    systemPrompt,
		ModelProviderId: "env-compatible",
		ModelId:         modelId,
		Temperature:     &temperature,
		MaxTurns:        &maxTurns,
		PermissionMode:  permissionMode,
		MemoryEnabled:   &memoryEnabled,
	}

	// 发送创建请求
	s := output.StartSpinner(" 创建 Agent 中...")
	var resp client.ApiResponse[client.AgentResponse]
	err = c.Post("/api/v1/agents", req, &resp)
	output.StopSpinner(s)

	if err != nil {
		output.Error(out, "创建失败: %s", err)
		return err
	}

	// 显示创建成功信息
	output.Success(out, "Agent 创建成功")
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("ID:"), resp.Data.ID)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("名称:"), resp.Data.Name)
	fmt.Fprintln(out)

	return nil
}

func createAgentWithOptions(out interface {
	Write([]byte) (int, error)
}, c *client.Client, opts createOptions) error {
	temperature := opts.temperature
	maxTurns := opts.maxTurns
	req := client.AgentCreateRequest{
		Name:             opts.name,
		SystemPrompt:     opts.systemPrompt,
		ModelProviderId:  opts.modelProviderID,
		ModelId:          opts.modelID,
		Temperature:      &temperature,
		MaxTurns:         &maxTurns,
		PermissionMode:   opts.permissionMode,
		MemoryEnabled:    &opts.memoryEnabled,
		RagEnabled:       &opts.ragEnabled,
		KnowledgeBaseIds: normalizeStringList(opts.knowledgeBaseIDs),
		EnabledToolIds:   normalizeStringList(opts.enabledToolIDs),
	}

	s := output.StartSpinner(" 创建 Agent 中...")
	var resp client.ApiResponse[client.AgentResponse]
	err := c.Post("/api/v1/agents", req, &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "创建失败: %s", err)
		return err
	}

	output.Success(out, "Agent 创建成功")
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("ID:"), resp.Data.ID)
	fmt.Fprintf(out, "  %s %s\n", color.HiBlackString("名称:"), resp.Data.Name)
	fmt.Fprintln(out)
	return nil
}

func normalizeStringList(items []string) []string {
	var result []string
	for _, item := range items {
		for _, part := range strings.Split(item, ",") {
			value := strings.TrimSpace(part)
			if value != "" {
				result = append(result, value)
			}
		}
	}
	if result == nil {
		return []string{}
	}
	return result
}
