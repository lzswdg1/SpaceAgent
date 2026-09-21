// Package mcp provides CLI commands for user-owned MCP server configuration.
package mcp

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

type serverFlags struct {
	name         string
	transport    string
	command      string
	argsText     string
	env          []string
	url          string
	headers      []string
	allowedTools []string
	disabled     bool
}

// NewMCPCmd creates MCP server and tool commands backed by /api/v1/mcp.
func NewMCPCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "mcp",
		Short: "MCP 服务管理",
		Long:  "管理当前账号的 MCP stdio / Streamable HTTP server，并查看或调用 MCP 暴露的工具。",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				output.ErrorWithHint(f.IOStreams.ErrOut, "请先登录", "使用 spaceagent auth login 登录")
				return fmt.Errorf("未登录")
			}
			return nil
		},
	}

	cmd.AddCommand(newListCmd(f))
	cmd.AddCommand(newCreateCmd(f))
	cmd.AddCommand(newUpdateCmd(f))
	cmd.AddCommand(newDeleteCmd(f))
	cmd.AddCommand(newTestCmd(f))
	cmd.AddCommand(newToolsCmd(f))
	cmd.AddCommand(newCallCmd(f))
	cmd.AddCommand(newAuditCmd(f))

	return cmd
}

func newListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "列出 MCP 服务",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runList(f)
		},
	}
}

func newCreateCmd(f *factory.Factory) *cobra.Command {
	flags := serverFlags{}
	cmd := &cobra.Command{
		Use:   "create",
		Short: "创建 MCP 服务",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runCreate(f, flags)
		},
	}
	addServerFlags(cmd, &flags, false)
	return cmd
}

func newUpdateCmd(f *factory.Factory) *cobra.Command {
	flags := serverFlags{}
	var enable bool
	cmd := &cobra.Command{
		Use:   "update <serverId>",
		Short: "更新 MCP 服务",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			enableChanged := cmd.Flags().Changed("enable")
			return runUpdate(f, args[0], flags, enable, enableChanged)
		},
	}
	addServerFlags(cmd, &flags, true)
	cmd.Flags().BoolVar(&enable, "enable", false, "启用服务")
	return cmd
}

func newDeleteCmd(f *factory.Factory) *cobra.Command {
	var yes bool
	cmd := &cobra.Command{
		Use:   "delete <serverId>",
		Short: "删除 MCP 服务",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runDelete(f, args[0], yes)
		},
	}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "跳过确认提示")
	return cmd
}

func newTestCmd(f *factory.Factory) *cobra.Command {
	flags := serverFlags{}
	cmd := &cobra.Command{
		Use:   "test [serverId]",
		Short: "测试 MCP 服务连接",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) == 1 {
				return runTestExisting(f, args[0])
			}
			return runTestConfig(f, flags)
		},
	}
	addServerFlags(cmd, &flags, false)
	return cmd
}

func newToolsCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "tools",
		Short: "列出 MCP 工具",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runTools(f)
		},
	}
}

func newCallCmd(f *factory.Factory) *cobra.Command {
	var jsonArgs string
	var kvArgs []string
	cmd := &cobra.Command{
		Use:   "call <toolId>",
		Short: "手动调用 MCP 工具",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			arguments, err := parseToolArguments(jsonArgs, kvArgs)
			if err != nil {
				return err
			}
			return runCall(f, args[0], arguments)
		},
	}
	cmd.Flags().StringVar(&jsonArgs, "json", "", "工具参数 JSON，例如 '{\"path\":\"/tmp\"}'")
	cmd.Flags().StringArrayVar(&kvArgs, "arg", nil, "工具参数 KEY=VALUE，可重复")
	return cmd
}

func newAuditCmd(f *factory.Factory) *cobra.Command {
	var limit int
	cmd := &cobra.Command{
		Use:   "audit",
		Short: "查看 MCP 工具调用审计",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runAudit(f, limit)
		},
	}
	cmd.Flags().IntVar(&limit, "limit", 50, "返回最近记录数（1-200）")
	return cmd
}

func addServerFlags(cmd *cobra.Command, flags *serverFlags, update bool) {
	cmd.Flags().StringVar(&flags.name, "name", "", "MCP 服务名称")
	cmd.Flags().StringVar(&flags.transport, "transport", "", "传输类型：stdio 或 streamable-http")
	cmd.Flags().StringVar(&flags.command, "command", "", "启动命令，例如 npx")
	cmd.Flags().StringVar(&flags.argsText, "args", "", "启动参数，按空格拆分")
	cmd.Flags().StringArrayVar(&flags.env, "env", nil, "环境变量 KEY=VALUE，可重复")
	cmd.Flags().StringVar(&flags.url, "url", "", "Streamable HTTP MCP 地址")
	cmd.Flags().StringArrayVar(&flags.headers, "header", nil, "HTTP 请求头 KEY=VALUE，可重复")
	cmd.Flags().StringArrayVar(&flags.allowedTools, "allow-tool", nil, "允许调用的工具名或前缀通配符，可重复；默认 *")
	cmd.Flags().BoolVar(&flags.disabled, "disabled", false, "创建或更新为禁用状态")
	if update {
		cmd.Long = "更新 MCP server。未传的字段会沿用当前配置。"
	}
}

func runList(f *factory.Factory) error {
	out := f.IOStreams.Out
	servers, err := listServers(f)
	if err != nil {
		output.Error(out, "获取 MCP 服务失败: %s", err)
		return err
	}
	if len(servers) == 0 {
		output.Info(out, "暂无 MCP 服务")
		output.Hint(out, "使用 spaceagent mcp create --name filesystem --command npx --args \"-y @modelcontextprotocol/server-filesystem /tmp\" 添加服务")
		return nil
	}
	renderServers(out, servers)
	return nil
}

func runCreate(f *factory.Factory, flags serverFlags) error {
	out := f.IOStreams.Out
	payload, err := buildPayloadFromFlags(flags, nil, !flags.disabled)
	if err != nil {
		return err
	}

	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[client.MCPServerConfigResponse]
	if err := c.Post("/api/v1/mcp/servers", payload, &resp); err != nil {
		output.Error(out, "创建 MCP 服务失败: %s", err)
		return err
	}

	output.Success(out, "MCP 服务已创建")
	renderServer(out, resp.Data)
	return nil
}

func runUpdate(f *factory.Factory, serverID string, flags serverFlags, enable bool, enableChanged bool) error {
	out := f.IOStreams.Out
	current, err := findServer(f, serverID)
	if err != nil {
		output.Error(out, "读取 MCP 服务失败: %s", err)
		return err
	}

	enabled := current.Enabled
	if flags.disabled {
		enabled = false
	}
	if enableChanged {
		enabled = enable
	}

	payload, err := buildPayloadFromFlags(flags, &current, enabled)
	if err != nil {
		return err
	}

	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[client.MCPServerConfigResponse]
	if err := c.Put("/api/v1/mcp/servers/"+serverID, payload, &resp); err != nil {
		output.Error(out, "更新 MCP 服务失败: %s", err)
		return err
	}

	output.Success(out, "MCP 服务已更新")
	renderServer(out, resp.Data)
	return nil
}

func runDelete(f *factory.Factory, serverID string, yes bool) error {
	out := f.IOStreams.Out
	if !yes {
		var confirmed bool
		prompt := &survey.Confirm{
			Message: fmt.Sprintf("确定要删除 MCP 服务 %s 吗？", serverID),
			Default: false,
		}
		if err := survey.AskOne(prompt, &confirmed); err != nil || !confirmed {
			output.Info(out, "操作已取消")
			return nil
		}
	}

	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[interface{}]
	if err := c.Delete("/api/v1/mcp/servers/"+serverID, &resp); err != nil {
		output.Error(out, "删除 MCP 服务失败: %s", err)
		return err
	}
	output.Success(out, "MCP 服务已删除: %s", serverID)
	return nil
}

func runTestExisting(f *factory.Factory, serverID string) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[client.MCPServerTestResponse]
	if err := c.Post("/api/v1/mcp/servers/"+serverID+"/test", nil, &resp); err != nil {
		output.Error(out, "测试 MCP 服务失败: %s", err)
		return err
	}
	renderTestResult(out, resp.Data)
	return nil
}

func runTestConfig(f *factory.Factory, flags serverFlags) error {
	out := f.IOStreams.Out
	payload, err := buildPayloadFromFlags(flags, nil, !flags.disabled)
	if err != nil {
		return err
	}
	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[client.MCPServerTestResponse]
	if err := c.Post("/api/v1/mcp/servers/test", payload, &resp); err != nil {
		output.Error(out, "测试 MCP 服务失败: %s", err)
		return err
	}
	renderTestResult(out, resp.Data)
	return nil
}

func runTools(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[[]client.MCPToolResponse]
	if err := c.Get("/api/v1/mcp/tools", &resp); err != nil {
		output.Error(out, "获取 MCP 工具失败: %s", err)
		return err
	}
	if len(resp.Data) == 0 {
		output.Info(out, "暂无 MCP 工具")
		return nil
	}
	table := output.NewTable(out, []string{"ID", "服务", "工具", "描述"})
	for _, tool := range resp.Data {
		table.Append([]string{
			output.TruncateString(tool.ID, 24),
			tool.ServerName,
			tool.Name,
			output.TruncateString(tool.Description, 48),
		})
	}
	table.Render()
	return nil
}

func runCall(f *factory.Factory, toolID string, arguments map[string]interface{}) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}
	var resp client.ApiResponse[client.MCPToolCallResponse]
	if err := c.Post("/api/v1/mcp/tools/call", client.MCPToolCallRequest{
		ToolID:    toolID,
		Arguments: arguments,
	}, &resp); err != nil {
		output.Error(out, "调用 MCP 工具失败: %s", err)
		return err
	}
	output.Success(out, "MCP 工具调用完成: %s", resp.Data.ToolName)
	renderJSON(out, resp.Data.Result)
	return nil
}

func runAudit(f *factory.Factory, limit int) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}
	if limit < 1 {
		limit = 1
	}
	if limit > 200 {
		limit = 200
	}
	var resp client.ApiResponse[[]client.MCPToolAuditResponse]
	if err := c.Get(fmt.Sprintf("/api/v1/mcp/audit?limit=%d", limit), &resp); err != nil {
		output.Error(out, "获取 MCP 审计记录失败: %s", err)
		return err
	}
	if len(resp.Data) == 0 {
		output.Info(out, "暂无 MCP 工具调用记录")
		return nil
	}
	table := output.NewTable(out, []string{"时间", "服务", "工具", "状态", "耗时"})
	for _, item := range resp.Data {
		table.Append([]string{
			item.CreatedAt,
			output.TruncateID(item.ServerID),
			item.ToolName,
			item.Status,
			fmt.Sprintf("%dms", item.DurationMs),
		})
	}
	table.Render()
	return nil
}

func listServers(f *factory.Factory) ([]client.MCPServerConfigResponse, error) {
	c, err := f.Client()
	if err != nil {
		return nil, err
	}
	var resp client.ApiResponse[[]client.MCPServerConfigResponse]
	if err := c.Get("/api/v1/mcp/servers", &resp); err != nil {
		return nil, err
	}
	return resp.Data, nil
}

func findServer(f *factory.Factory, serverID string) (client.MCPServerConfigResponse, error) {
	servers, err := listServers(f)
	if err != nil {
		return client.MCPServerConfigResponse{}, err
	}
	for _, server := range servers {
		if server.ID == serverID {
			return server, nil
		}
	}
	return client.MCPServerConfigResponse{}, fmt.Errorf("MCP 服务不存在: %s", serverID)
}

func buildPayloadFromFlags(flags serverFlags, current *client.MCPServerConfigResponse, enabled bool) (client.MCPServerConfigRequest, error) {
	name := strings.TrimSpace(flags.name)
	transport := strings.ToLower(strings.TrimSpace(flags.transport))
	command := strings.TrimSpace(flags.command)
	url := strings.TrimSpace(flags.url)
	args := splitArgs(flags.argsText)
	env, err := parseEnv(flags.env)
	if err != nil {
		return client.MCPServerConfigRequest{}, err
	}

	if current != nil {
		if name == "" {
			name = current.Name
		}
		if transport == "" {
			transport = current.Transport
		}
		if command == "" {
			command = current.Command
		}
		if url == "" {
			url = current.URL
		}
		if flags.argsText == "" {
			args = current.Args
		}
		if len(flags.env) == 0 {
			env = current.Env
		}
		if len(flags.headers) == 0 {
			flags.headers = mapToPairs(current.Headers)
		}
		if len(flags.allowedTools) == 0 {
			flags.allowedTools = current.AllowedTools
		}
	}
	if transport == "" {
		transport = "stdio"
	}
	if transport == "http" || transport == "streamable_http" {
		transport = "streamable-http"
	}
	if transport != "stdio" && transport != "streamable-http" {
		return client.MCPServerConfigRequest{}, fmt.Errorf("不支持的 MCP 传输类型: %s", transport)
	}

	if name == "" {
		if err := survey.AskOne(&survey.Input{Message: "服务名称:"}, &name, survey.WithValidator(survey.Required)); err != nil {
			return client.MCPServerConfigRequest{}, err
		}
	}
	if transport == "stdio" && command == "" {
		if err := survey.AskOne(&survey.Input{Message: "启动命令:"}, &command, survey.WithValidator(survey.Required)); err != nil {
			return client.MCPServerConfigRequest{}, err
		}
	}
	if transport == "streamable-http" && url == "" {
		if err := survey.AskOne(&survey.Input{Message: "MCP URL:"}, &url, survey.WithValidator(survey.Required)); err != nil {
			return client.MCPServerConfigRequest{}, err
		}
	}
	headers, err := parseEnv(flags.headers)
	if err != nil {
		return client.MCPServerConfigRequest{}, fmt.Errorf("请求头格式错误: %w", err)
	}
	allowedTools := flags.allowedTools
	if allowedTools == nil {
		allowedTools = []string{"*"}
	}

	return client.MCPServerConfigRequest{
		Name:         name,
		Transport:    transport,
		Command:      command,
		Args:         args,
		Env:          env,
		URL:          url,
		Headers:      headers,
		AllowedTools: allowedTools,
		Enabled:      &enabled,
	}, nil
}

func mapToPairs(values map[string]string) []string {
	pairs := make([]string, 0, len(values))
	for key, value := range values {
		pairs = append(pairs, key+"="+value)
	}
	return pairs
}

func splitArgs(value string) []string {
	fields := strings.Fields(strings.TrimSpace(value))
	if fields == nil {
		return []string{}
	}
	return fields
}

func parseEnv(items []string) (map[string]string, error) {
	env := map[string]string{}
	for _, item := range items {
		parts := strings.SplitN(item, "=", 2)
		if len(parts) != 2 || strings.TrimSpace(parts[0]) == "" {
			return nil, fmt.Errorf("环境变量格式错误: %s，应为 KEY=VALUE", item)
		}
		env[strings.TrimSpace(parts[0])] = parts[1]
	}
	return env, nil
}

func parseToolArguments(jsonArgs string, kvArgs []string) (map[string]interface{}, error) {
	args := map[string]interface{}{}
	if strings.TrimSpace(jsonArgs) != "" {
		if err := json.Unmarshal([]byte(jsonArgs), &args); err != nil {
			return nil, fmt.Errorf("解析 JSON 参数失败: %w", err)
		}
	}
	for _, item := range kvArgs {
		parts := strings.SplitN(item, "=", 2)
		if len(parts) != 2 || strings.TrimSpace(parts[0]) == "" {
			return nil, fmt.Errorf("工具参数格式错误: %s，应为 KEY=VALUE", item)
		}
		args[strings.TrimSpace(parts[0])] = parts[1]
	}
	return args, nil
}

func renderServers(out interface {
	Write([]byte) (int, error)
}, servers []client.MCPServerConfigResponse) {
	table := output.NewTable(out, []string{"ID", "名称", "传输", "端点", "启用"})
	for _, server := range servers {
		enabled := "否"
		if server.Enabled {
			enabled = "是"
		}
		endpoint := server.Command + " " + strings.Join(server.Args, " ")
		if server.Transport == "streamable-http" {
			endpoint = server.URL
		}
		table.Append([]string{
			output.TruncateID(server.ID),
			server.Name,
			server.Transport,
			output.TruncateString(strings.TrimSpace(endpoint), 48),
			enabled,
		})
	}
	table.Render()
}

func renderServer(out interface {
	Write([]byte) (int, error)
}, server client.MCPServerConfigResponse) {
	fmt.Fprintf(out, "  ID:     %s\n", server.ID)
	fmt.Fprintf(out, "  名称:   %s\n", server.Name)
	fmt.Fprintf(out, "  传输:   %s\n", server.Transport)
	if server.Transport == "streamable-http" {
		fmt.Fprintf(out, "  URL:    %s\n", server.URL)
	} else {
		fmt.Fprintf(out, "  命令:   %s %s\n", server.Command, strings.Join(server.Args, " "))
	}
	fmt.Fprintf(out, "  工具:   %s\n", strings.Join(server.AllowedTools, ", "))
	fmt.Fprintf(out, "  启用:   %v\n", server.Enabled)
}

func renderTestResult(out interface {
	Write([]byte) (int, error)
}, result client.MCPServerTestResponse) {
	if result.OK {
		output.Success(out, "MCP 服务可用，发现 %d 个工具", result.ToolCount)
	} else {
		output.Error(out, "MCP 服务不可用: %s", result.Message)
	}
	if len(result.Tools) > 0 {
		table := output.NewTable(out, []string{"工具", "描述"})
		for _, tool := range result.Tools {
			table.Append([]string{tool.Name, output.TruncateString(tool.Description, 60)})
		}
		table.Render()
	}
}

func renderJSON(out interface {
	Write([]byte) (int, error)
}, value interface{}) {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		fmt.Fprintf(out, "  %v\n", value)
		return
	}
	fmt.Fprintf(out, "%s\n", string(data))
}
