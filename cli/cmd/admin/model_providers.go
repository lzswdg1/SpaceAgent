// providers.go 实现模型提供商管理命令。
package admin

import (
	"fmt"
	"os"
	"strings"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewProviderCmd exposes user-owned model provider management backed by platform-server.
func NewProviderCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "provider",
		Short: "管理模型提供商与模型",
		Long:  "管理当前用户的 OpenAI-Compatible 模型提供商、API Key 和可用模型。",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				output.ErrorWithHint(f.IOStreams.ErrOut, "请先登录", "使用 spaceagent auth login 登录")
				return fmt.Errorf("未登录")
			}
			return nil
		},
	}
	list := NewProvidersListCmd(f)
	list.Use = "list"
	add := NewAddProviderCmd(f)
	add.Use = "add"
	cmd.AddCommand(
		list,
		add,
		NewUpdateProviderCmd(f),
		NewDeleteProviderCmd(f),
		NewModelsListCmd(f),
		NewAddModelCmd(f),
	)
	return cmd
}

type ProviderResponse struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Type         string `json:"type"`
	BaseUrl      string `json:"baseUrl"`
	ApiKey       string `json:"apiKey"`
	AuthType     string `json:"authType"`
	ProxyEnabled bool   `json:"proxyEnabled"`
	IsDefault    bool   `json:"isDefault"`
	Enabled      bool   `json:"enabled"`
	CreatedAt    string `json:"createdAt"`
}

type ModelResponse struct {
	ID               string `json:"id"`
	ProviderId       string `json:"providerId"`
	ModelId          string `json:"modelId"`
	DisplayName      string `json:"displayName"`
	IsDefault        bool   `json:"isDefault"`
	MaxContextTokens int    `json:"maxContextTokens"`
	CreatedAt        string `json:"createdAt"`
}

type AvailableModelResponse struct {
	ID               string `json:"id"`
	ModelId          string `json:"modelId"`
	DisplayName      string `json:"displayName"`
	MaxContextTokens int    `json:"maxContextTokens"`
}

// NewProvidersListCmd 列出所有模型提供商。
func NewProvidersListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "providers",
		Short: "列出模型提供商",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runListProviders(f)
		},
	}
}

// NewAddProviderCmd 添加模型提供商。
func NewAddProviderCmd(f *factory.Factory) *cobra.Command {
	var name string
	var providerType string
	var baseURL string
	var apiKey string
	var authType string
	var makeDefault bool
	cmd := &cobra.Command{
		Use:   "add-provider",
		Short: "添加模型提供商",
		RunE: func(cmd *cobra.Command, args []string) error {
			if cmd.Flags().NFlag() > 0 {
				if strings.TrimSpace(apiKey) == "" {
					apiKey = os.Getenv("SPACEAGENT_PROVIDER_API_KEY")
				}
				if name == "" || baseURL == "" || apiKey == "" {
					return fmt.Errorf("非交互模式必须提供 --name、--base-url，以及 --api-key 或 SPACEAGENT_PROVIDER_API_KEY")
				}
				return runCreateProvider(f, name, providerType, baseURL, apiKey, authType, makeDefault)
			}
			return runAddProvider(f)
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "提供商名称")
	cmd.Flags().StringVar(&providerType, "type", "openai-compatible", "协议类型")
	cmd.Flags().StringVar(&baseURL, "base-url", "", "OpenAI-Compatible Base URL")
	cmd.Flags().StringVar(&apiKey, "api-key", "", "API Key；自动化场景建议使用 SPACEAGENT_PROVIDER_API_KEY")
	cmd.Flags().StringVar(&authType, "auth", "bearer", "认证方式")
	cmd.Flags().BoolVar(&makeDefault, "default", false, "设为默认提供商")
	return cmd
}

// NewModelsListCmd 列出所有可用模型。
func NewModelsListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "models",
		Short: "列出所有可用模型",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runListModels(f)
		},
	}
}

// NewAddModelCmd 为提供商添加模型。
func NewAddModelCmd(f *factory.Factory) *cobra.Command {
	var modelID string
	var displayName string
	var maxContextTokens int
	var makeDefault bool
	cmd := &cobra.Command{
		Use:   "add-model [提供商ID]",
		Short: "为提供商添加模型",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if cmd.Flags().NFlag() > 0 {
				if modelID == "" {
					return fmt.Errorf("非交互模式必须提供 --model")
				}
				if displayName == "" {
					displayName = modelID
				}
				return runCreateModel(f, args[0], modelID, displayName, maxContextTokens, makeDefault)
			}
			return runAddModel(f, args[0])
		},
	}
	cmd.Flags().StringVar(&modelID, "model", "", "模型 ID")
	cmd.Flags().StringVar(&displayName, "name", "", "显示名称")
	cmd.Flags().IntVar(&maxContextTokens, "context-tokens", 32768, "模型上下文窗口")
	cmd.Flags().BoolVar(&makeDefault, "default", false, "设为该提供商的默认模型")
	return cmd
}

func runListProviders(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载提供商列表...")
	var resp client.ApiResponse[[]ProviderResponse]
	err = c.Get("/api/v1/model-providers", &resp)
	output.StopSpinner(s)

	if err != nil {
		output.Error(out, "获取提供商列表失败: %s", err)
		return err
	}

	if len(resp.Data) == 0 {
		output.Info(out, "暂无模型提供商")
		output.Hint(out, "使用 spaceagent provider add 添加")
		return nil
	}

	table := output.NewTable(out, []string{"ID", "名称", "类型", "Base URL", "API Key", "启用", "默认"})
	for _, p := range resp.Data {
		isDefault := "否"
		if p.IsDefault {
			isDefault = "是"
		}
		enabled := "否"
		if p.Enabled {
			enabled = "是"
		}
		table.Append([]string{
			output.TruncateID(p.ID),
			p.Name,
			p.Type,
			output.TruncateString(p.BaseUrl, 30),
			p.ApiKey,
			enabled,
			isDefault,
		})
	}
	table.Render()
	return nil
}

func runAddProvider(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	var name, providerType, baseUrl, apiKey, authType string
	var makeDefault bool

	if err := survey.AskOne(&survey.Input{Message: "提供商名称:"}, &name); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Select{
		Message: "协议类型:",
		Options: []string{"openai-compatible"},
	}, &providerType); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Input{Message: "Base URL:"}, &baseUrl); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Password{Message: "API Key:"}, &apiKey); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Select{
		Message: "认证方式:",
		Options: []string{"bearer"},
		Default: "bearer",
	}, &authType); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Confirm{Message: "设为默认提供商?", Default: false}, &makeDefault); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	return createProvider(c, out, name, providerType, baseUrl, apiKey, authType, makeDefault)
}

func runCreateProvider(
	f *factory.Factory,
	name string,
	providerType string,
	baseURL string,
	apiKey string,
	authType string,
	makeDefault bool,
) error {
	c, err := f.Client()
	if err != nil {
		return err
	}
	return createProvider(c, f.IOStreams.Out, name, providerType, baseURL, apiKey, authType, makeDefault)
}

func createProvider(
	c *client.Client,
	out interface{ Write([]byte) (int, error) },
	name string,
	providerType string,
	baseURL string,
	apiKey string,
	authType string,
	makeDefault bool,
) error {
	s := output.StartSpinner(" 创建提供商...")
	reqBody := map[string]interface{}{
		"name":      name,
		"type":      providerType,
		"baseUrl":   baseURL,
		"apiKey":    apiKey,
		"authType":  authType,
		"isDefault": makeDefault,
	}
	var resp client.ApiResponse[ProviderResponse]
	err := c.Post("/api/v1/model-providers", reqBody, &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "创建失败: %s", err)
		return err
	}
	output.Success(out, "提供商已创建: %s (%s)", resp.Data.Name, output.TruncateID(resp.Data.ID))
	output.Hint(out, "使用 spaceagent provider add-model %s 添加模型", resp.Data.ID)
	return nil
}

func runListModels(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载模型列表...")
	var resp client.ApiResponse[[]AvailableModelResponse]
	err = c.Get("/api/v1/models/available", &resp)
	output.StopSpinner(s)

	if err != nil {
		output.Error(out, "获取模型列表失败: %s", err)
		return err
	}

	if len(resp.Data) == 0 {
		output.Info(out, "暂无可用模型")
		output.Hint(out, "先添加提供商: spaceagent provider add")
		return nil
	}

	table := output.NewTable(out, []string{"ID", "Model ID", "显示名称", "上下文窗口"})
	for _, m := range resp.Data {
		table.Append([]string{
			output.TruncateID(m.ID),
			m.ModelId,
			m.DisplayName,
			fmt.Sprintf("%dK", m.MaxContextTokens/1000),
		})
	}
	table.Render()
	return nil
}

func runAddModel(f *factory.Factory, providerId string) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	var modelId, displayName string
	var maxTokens int
	var makeDefault bool

	if err := survey.AskOne(&survey.Input{Message: "模型 ID (如 claude-sonnet-4-20250514):"}, &modelId); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	if err := survey.AskOne(&survey.Input{Message: "显示名称 (如 Claude Sonnet 4):"}, &displayName); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}
	maxTokens = 200000
	if err := survey.AskOne(&survey.Confirm{Message: "设为该提供商的默认模型?", Default: false}, &makeDefault); err != nil {
		output.Info(out, "操作已取消")
		return nil
	}

	if err := createModel(c, out, providerId, modelId, displayName, maxTokens, makeDefault); err != nil {
		return err
	}

	// 提示是否继续添加
	var addMore bool
	_ = survey.AskOne(&survey.Confirm{Message: "继续添加模型?", Default: false}, &addMore)
	if addMore {
		return runAddModel(f, providerId)
	}

	return nil
}

func runCreateModel(
	f *factory.Factory,
	providerID string,
	modelID string,
	displayName string,
	maxContextTokens int,
	makeDefault bool,
) error {
	c, err := f.Client()
	if err != nil {
		return err
	}
	return createModel(c, f.IOStreams.Out, providerID, modelID, displayName, maxContextTokens, makeDefault)
}

func createModel(
	c *client.Client,
	out interface{ Write([]byte) (int, error) },
	providerID string,
	modelID string,
	displayName string,
	maxContextTokens int,
	makeDefault bool,
) error {
	if maxContextTokens < 1024 {
		return fmt.Errorf("context-tokens 不能小于 1024")
	}
	s := output.StartSpinner(" 添加模型...")
	reqBody := map[string]interface{}{
		"modelId":          modelID,
		"displayName":      displayName,
		"maxContextTokens": maxContextTokens,
		"isDefault":        makeDefault,
	}
	var resp client.ApiResponse[ModelResponse]
	err := c.Post(fmt.Sprintf("/api/v1/model-providers/%s/models", providerID), reqBody, &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "添加失败: %s", err)
		return err
	}
	output.Success(out, "模型已添加: %s (%s)", resp.Data.DisplayName, resp.Data.ModelId)
	output.Hint(out, "使用 spaceagent chat send -m %s '消息' 指定此模型", resp.Data.ModelId)
	return nil
}
