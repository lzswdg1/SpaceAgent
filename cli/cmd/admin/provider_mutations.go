package admin

import (
	"fmt"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

type providerUpdateOptions struct {
	Name           string
	ProviderType   string
	BaseURL        string
	APIKey         string
	AuthType       string
	Enabled        bool
	Default        bool
	NameChanged    bool
	TypeChanged    bool
	BaseURLChanged bool
	APIKeyChanged  bool
	AuthChanged    bool
	EnabledChanged bool
	DefaultChanged bool
}

func NewUpdateProviderCmd(f *factory.Factory) *cobra.Command {
	var opts providerUpdateOptions
	cmd := &cobra.Command{
		Use:   "update <provider-id>",
		Short: "更新模型提供商",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			opts.NameChanged = cmd.Flags().Changed("name")
			opts.TypeChanged = cmd.Flags().Changed("type")
			opts.BaseURLChanged = cmd.Flags().Changed("base-url")
			opts.APIKeyChanged = cmd.Flags().Changed("api-key")
			opts.AuthChanged = cmd.Flags().Changed("auth")
			opts.EnabledChanged = cmd.Flags().Changed("enabled")
			opts.DefaultChanged = cmd.Flags().Changed("default")
			return runUpdateProvider(f, args[0], opts)
		},
	}

	cmd.Flags().StringVar(&opts.Name, "name", "", "提供商名称")
	cmd.Flags().StringVar(&opts.ProviderType, "type", "", "协议类型")
	cmd.Flags().StringVar(&opts.BaseURL, "base-url", "", "OpenAI-Compatible Base URL")
	cmd.Flags().StringVar(&opts.APIKey, "api-key", "", "替换 API Key；不传则保留原 Key")
	cmd.Flags().StringVar(&opts.AuthType, "auth", "", "认证方式")
	cmd.Flags().BoolVar(&opts.Enabled, "enabled", true, "是否启用")
	cmd.Flags().BoolVar(&opts.Default, "default", false, "是否设为默认提供商")
	return cmd
}

func NewDeleteProviderCmd(f *factory.Factory) *cobra.Command {
	var yes bool
	cmd := &cobra.Command{
		Use:   "delete <provider-id>",
		Short: "删除模型提供商",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runDeleteProvider(f, args[0], yes)
		},
	}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "跳过确认")
	return cmd
}

func runUpdateProvider(f *factory.Factory, providerID string, opts providerUpdateOptions) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	provider, err := loadProvider(c, providerID)
	if err != nil {
		output.Error(out, "读取提供商失败: %s", err)
		return err
	}

	name := provider.Name
	if opts.NameChanged {
		name = opts.Name
	}
	providerType := provider.Type
	if opts.TypeChanged {
		providerType = opts.ProviderType
	}
	baseURL := provider.BaseUrl
	if opts.BaseURLChanged {
		baseURL = opts.BaseURL
	}
	authType := provider.AuthType
	if opts.AuthChanged {
		authType = opts.AuthType
	}
	enabled := provider.Enabled
	if opts.EnabledChanged {
		enabled = opts.Enabled
	}
	makeDefault := provider.IsDefault
	if opts.DefaultChanged {
		makeDefault = opts.Default
	}
	apiKey := ""
	if opts.APIKeyChanged {
		apiKey = opts.APIKey
	}

	request := map[string]interface{}{
		"name":      name,
		"type":      providerType,
		"baseUrl":   baseURL,
		"apiKey":    apiKey,
		"authType":  authType,
		"enabled":   enabled,
		"isDefault": makeDefault,
	}
	var resp client.ApiResponse[ProviderResponse]
	if err := c.Put("/api/v1/model-providers/"+providerID, request, &resp); err != nil {
		output.Error(out, "更新提供商失败: %s", err)
		return err
	}

	output.Success(out, "提供商已更新: %s (%s)", resp.Data.Name, output.TruncateID(resp.Data.ID))
	return nil
}

func runDeleteProvider(f *factory.Factory, providerID string, yes bool) error {
	out := f.IOStreams.Out
	if !yes {
		confirmed := false
		if err := survey.AskOne(&survey.Confirm{
			Message: "确定删除这个模型提供商? 已绑定 Agent 时后端会拒绝删除。",
			Default: false,
		}, &confirmed); err != nil || !confirmed {
			output.Info(out, "操作已取消")
			return nil
		}
	}

	c, err := f.Client()
	if err != nil {
		return err
	}
	if err := c.Delete("/api/v1/model-providers/"+providerID, nil); err != nil {
		output.Error(out, "删除提供商失败: %s", err)
		return err
	}
	output.Success(out, "提供商已删除: %s", providerID)
	return nil
}

func loadProvider(c *client.Client, providerID string) (ProviderResponse, error) {
	var resp client.ApiResponse[[]ProviderResponse]
	if err := c.Get("/api/v1/model-providers", &resp); err != nil {
		return ProviderResponse{}, err
	}
	for _, provider := range resp.Data {
		if provider.ID == providerID {
			return provider, nil
		}
	}
	return ProviderResponse{}, fmt.Errorf("模型提供商不存在: %s", providerID)
}
