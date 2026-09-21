// Package project provides MCP-backed SourceRepository and Local Workspace Bridge commands.
package project

import (
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewProjectCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "project",
		Short: "管理项目源码和本地 Workspace Bridge",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				return fmt.Errorf("请先使用 spaceagent auth login 登录")
			}
			return nil
		},
	}
	cmd.AddCommand(newBridgeCmd(f), newSourceCmd(f), newWorkspaceCmd(f))
	return cmd
}

func newWorkspaceCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{Use: "workspace", Short: "履行 Local Bridge Workspace 命令"}
	cmd.AddCommand(&cobra.Command{
		Use: "fulfill <bridge-id>", Short: "在本机创建隔离 Git worktree 并回报 opaque locator/HEAD",
		Args: cobra.ExactArgs(1), RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil {
				return err
			}
			local, err := config.FindWorkspaceBridge(cfg.Profile, args[0])
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[[]client.BridgeWorkspaceCommandResponse]
			if err := c.Get("/api/v1/workspace-bridges/"+url.PathEscape(args[0])+"/workspace-commands", &response); err != nil {
				return err
			}
			for _, item := range response.Data {
				if item.RootHandle != local.RootHandle {
					return fmt.Errorf("Bridge rootHandle 不匹配")
				}
				target := filepath.Join(filepath.Dir(local.LocalPath), ".spaceagent-worktrees", item.WorkspaceID)
				if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
					return err
				}
				outputBytes, gitErr := exec.Command("git", "-C", local.LocalPath, "worktree", "add", "-b", item.BranchName, target, item.BaseRef).CombinedOutput()
				completion := client.CompleteBridgeWorkspaceRequest{Success: gitErr == nil}
				if gitErr == nil {
					head, err := exec.Command("git", "-C", target, "rev-parse", "HEAD").Output()
					if err != nil {
						return err
					}
					completion.OpaqueLocator = "localw_" + strings.ReplaceAll(uuid.NewString(), "-", "")
					completion.HeadCommit = strings.TrimSpace(string(head))
				} else {
					completion.FailureReason = strings.TrimSpace(string(outputBytes))
				}
				var completed client.ApiResponse[any]
				if err := c.PostWithHeaders("/api/v1/workspace-bridges/workspace-commands/"+url.PathEscape(item.ID)+"/complete", completion, map[string]string{"X-SpaceAgent-Bridge-Token": local.BridgeToken}, &completed); err != nil {
					return err
				}
				if gitErr != nil {
					return fmt.Errorf("git worktree 失败: %s", completion.FailureReason)
				}
			}
			output.Success(f.IOStreams.Out, "已履行 %d 个 Workspace 命令", len(response.Data))
			return nil
		},
	})
	return cmd
}

func newBridgeCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{Use: "bridge", Short: "管理本地 Workspace Bridge"}
	cmd.AddCommand(newBridgeRegisterCmd(f), newBridgeListCmd(f),
		newBridgeHeartbeatCmd(f), newBridgeRevokeCmd(f))
	return cmd
}

func newBridgeRegisterCmd(f *factory.Factory) *cobra.Command {
	var name, device string
	cmd := &cobra.Command{
		Use:   "register <local-directory>",
		Short: "在本机保存目录并向平台注册不透明 rootHandle",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			registration, err := registerLocalBridge(f, args[0], name, device)
			if err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "Workspace Bridge 已注册: %s", registration.BridgeID)
			output.Info(f.IOStreams.Out, "rootHandle: %s", registration.RootHandle)
			return nil
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "Bridge 显示名称（默认目录名）")
	cmd.Flags().StringVar(&device, "device", "", "设备标识（默认主机名）")
	return cmd
}

func newBridgeListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use: "list", Short: "列出平台登记的本地 Bridge",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[[]client.LocalWorkspaceBridgeResponse]
			if err := c.Get("/api/v1/workspace-bridges", &response); err != nil {
				return err
			}
			table := output.NewTable(f.IOStreams.Out, []string{"ID", "名称", "设备", "rootHandle", "状态"})
			for _, bridge := range response.Data {
				table.Append([]string{output.TruncateID(bridge.ID), bridge.DisplayName,
					bridge.DeviceID, bridge.RootHandle, bridge.State})
			}
			table.Render()
			return nil
		},
	}
}

func newBridgeHeartbeatCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use: "heartbeat <bridge-id>", Short: "使用本机 Bridge Token 发送心跳",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil {
				return err
			}
			local, err := config.FindWorkspaceBridge(cfg.Profile, args[0])
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.LocalWorkspaceBridgeResponse]
			if err := c.PostWithHeaders(
				"/api/v1/workspace-bridges/"+url.PathEscape(args[0])+"/heartbeat", nil,
				map[string]string{"X-SpaceAgent-Bridge-Token": local.BridgeToken}, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "Bridge 心跳成功: %s", response.Data.ID)
			return nil
		},
	}
}

func newBridgeRevokeCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use: "revoke <bridge-id>", Short: "撤销 Bridge 并清除本机 Token",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := f.Client()
			if err != nil {
				return err
			}
			if err := c.Delete("/api/v1/workspace-bridges/"+url.PathEscape(args[0]), nil); err != nil {
				return err
			}
			cfg, err := f.Config()
			if err != nil {
				return err
			}
			if err := config.RemoveWorkspaceBridge(cfg.Profile, args[0]); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "Bridge 已撤销: %s", args[0])
			return nil
		},
	}
}

func newSourceCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{Use: "source", Short: "管理 Project SourceRepository"}
	cmd.AddCommand(newSourceListCmd(f), newImportLocalCmd(f), newImportGithubCmd(f))
	return cmd
}

func newSourceListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use: "list <project-id>", Short: "列出 Project 的 SourceRepository",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[[]client.SourceRepositoryResponse]
			if err := c.Get("/api/v1/projects/"+url.PathEscape(args[0])+"/sources", &response); err != nil {
				return err
			}
			table := output.NewTable(f.IOStreams.Out, []string{"ID", "名称", "类型", "可见性", "分支", "状态"})
			for _, source := range response.Data {
				table.Append([]string{output.TruncateID(source.ID), source.DisplayName,
					source.Type, source.Visibility, source.DefaultBranch, source.State})
			}
			table.Render()
			return nil
		},
	}
}

func newImportLocalCmd(f *factory.Factory) *cobra.Command {
	var name, branch, device string
	cmd := &cobra.Command{
		Use:   "import-local <project-id> <local-directory>",
		Short: "注册本地 Bridge 并导入不透明 SourceRepository 引用",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			registration, err := registerLocalBridge(f, args[1], name, device)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			if strings.TrimSpace(branch) == "" {
				branch = "main"
			}
			if strings.TrimSpace(name) == "" {
				name = filepath.Base(registration.LocalPath)
			}
			var response client.ApiResponse[client.SourceRepositoryResponse]
			request := client.ImportLocalRepositoryRequest{
				BridgeID: registration.BridgeID, RootHandle: registration.RootHandle,
				DisplayName: name, DefaultBranch: branch,
			}
			if err := c.Post("/api/v1/projects/"+url.PathEscape(args[0])+"/sources/local", request, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "本地源码已导入: %s", response.Data.ID)
			return nil
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "源码显示名称")
	cmd.Flags().StringVar(&branch, "branch", "main", "默认分支")
	cmd.Flags().StringVar(&device, "device", "", "设备标识")
	return cmd
}

func newImportGithubCmd(f *factory.Factory) *cobra.Command {
	var connection, providerRepositoryID string
	cmd := &cobra.Command{
		Use: "import-github <project-id> <owner/repository>", Short: "通过 GitHub MCP 导入仓库",
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			parts := strings.Split(args[1], "/")
			if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
				return fmt.Errorf("仓库必须为 owner/repository")
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.SourceRepositoryResponse]
			request := client.ImportGithubMcpRepositoryRequest{
				ConnectionID:         connection,
				ProviderRepositoryID: providerRepositoryID,
				GithubURL:            "https://github.com/" + parts[0] + "/" + parts[1],
			}
			if err := c.PostWithHeaders(
				"/api/v1/projects/"+url.PathEscape(args[0])+"/sources/github-mcp",
				request, map[string]string{"Idempotency-Key": uuid.NewString()}, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "GitHub 源码已导入: %s", response.Data.ID)
			return nil
		},
	}
	cmd.Flags().StringVar(&connection, "connection", "", "GitHub MCP Connection ID")
	cmd.Flags().StringVar(&providerRepositoryID, "provider-repository-id", "", "可选的 GitHub repository ID")
	_ = cmd.MarkFlagRequired("connection")
	return cmd
}

func registerLocalBridge(f *factory.Factory, path, name, device string) (config.WorkspaceBridgeRegistration, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return config.WorkspaceBridgeRegistration{}, err
	}
	resolved, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return config.WorkspaceBridgeRegistration{}, fmt.Errorf("读取本地目录失败: %w", err)
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return config.WorkspaceBridgeRegistration{}, fmt.Errorf("本地目录不存在或不是目录")
	}
	if strings.TrimSpace(name) == "" {
		name = filepath.Base(resolved)
	}
	if strings.TrimSpace(device) == "" {
		device, _ = os.Hostname()
	}
	if strings.TrimSpace(device) == "" {
		device = "local-device"
	}
	rootHandle := uuid.NewString()
	c, err := f.Client()
	if err != nil {
		return config.WorkspaceBridgeRegistration{}, err
	}
	var response client.ApiResponse[client.CreatedLocalWorkspaceBridgeResponse]
	request := client.RegisterLocalWorkspaceBridgeRequest{
		DisplayName: name, DeviceID: device, RootHandle: rootHandle,
	}
	if err := c.Post("/api/v1/workspace-bridges", request, &response); err != nil {
		return config.WorkspaceBridgeRegistration{}, err
	}
	if response.Data.Bridge.ID == "" || response.Data.BridgeToken == "" ||
		response.Data.Bridge.RootHandle != rootHandle {
		return config.WorkspaceBridgeRegistration{}, fmt.Errorf("平台返回了无效的 Bridge 注册结果")
	}
	cfg, err := f.Config()
	if err != nil {
		return config.WorkspaceBridgeRegistration{}, err
	}
	registration := config.WorkspaceBridgeRegistration{
		BridgeID: response.Data.Bridge.ID, RootHandle: rootHandle, LocalPath: resolved,
		BridgeToken: response.Data.BridgeToken, DisplayName: name, DeviceID: device,
	}
	if err := config.SaveWorkspaceBridge(cfg.Profile, registration); err != nil {
		return config.WorkspaceBridgeRegistration{}, err
	}
	return registration, nil
}
