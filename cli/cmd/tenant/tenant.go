// Package tenant implements SaaS workspace and membership management commands.
package tenant

import (
	"fmt"
	"net/url"
	"strings"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

var validRoles = map[string]bool{
	"ADMIN": true, "MEMBER": true, "VIEWER": true,
}

func NewTenantCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "tenant",
		Short: "管理 SaaS 工作区和成员",
		PersistentPreRunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil || !cfg.IsLoggedIn() {
				output.ErrorWithHint(f.IOStreams.ErrOut, "请先登录", "使用 spaceagent auth login 登录")
				return fmt.Errorf("未登录")
			}
			return nil
		},
	}
	cmd.AddCommand(
		newListCmd(f),
		newCreateCmd(f),
		newUpdateCmd(f),
		newSwitchCmd(f),
		newMembersCmd(f),
		newAddMemberCmd(f),
		newSetRoleCmd(f),
		newRemoveMemberCmd(f),
		newTransferOwnershipCmd(f),
		newLeaveCmd(f),
		newArchiveCmd(f),
		newInviteCmd(f),
		newInvitationsCmd(f),
		newIncomingInvitationsCmd(f),
		newAcceptInvitationCmd(f),
		newDeclineInvitationCmd(f),
		newRevokeInvitationCmd(f),
		newAuditCmd(f),
	)
	return cmd
}

func newUpdateCmd(f *factory.Factory) *cobra.Command {
	var tenantRef, name, slug string
	cmd := &cobra.Command{
		Use:   "update",
		Short: "修改组织名称或 slug",
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(name) == "" && strings.TrimSpace(slug) == "" {
				return fmt.Errorf("至少提供 --name 或 --slug")
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.TenantResponse]
			if err := c.Patch(
				"/api/v1/tenants/"+url.PathEscape(tenantID),
				client.UpdateTenantRequest{Name: strings.TrimSpace(name), Slug: strings.TrimSpace(slug)},
				&response,
			); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "工作区已更新: %s (%s)", response.Data.Name, response.Data.Slug)
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().StringVar(&name, "name", "", "新名称")
	cmd.Flags().StringVar(&slug, "slug", "", "新 slug（仅 OWNER）")
	return cmd
}

func newListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "列出可访问的工作区",
		RunE: func(cmd *cobra.Command, args []string) error {
			tenants, err := listTenants(f)
			if err != nil {
				return err
			}
			cfg, _ := f.Config()
			table := output.NewTable(f.IOStreams.Out, []string{"当前", "ID", "Slug", "名称", "类型", "角色"})
			for _, item := range tenants {
				current := ""
				if item.ID == cfg.TenantID {
					current = "*"
				}
				table.Append([]string{current, output.TruncateID(item.ID), item.Slug, item.Name, item.Type, item.CurrentUserRole})
			}
			if len(tenants) == 0 {
				output.Info(f.IOStreams.Out, "暂无可访问的工作区")
				return nil
			}
			table.Render()
			return nil
		},
	}
}

func newCreateCmd(f *factory.Factory) *cobra.Command {
	var name, slug string
	cmd := &cobra.Command{
		Use:   "create",
		Short: "创建组织工作区",
		RunE: func(cmd *cobra.Command, args []string) error {
			name = strings.TrimSpace(name)
			if name == "" {
				return fmt.Errorf("--name 不能为空")
			}
			var response client.ApiResponse[client.TenantResponse]
			c, err := f.Client()
			if err != nil {
				return err
			}
			if err := c.Post("/api/v1/tenants", client.CreateTenantRequest{Name: name, Slug: strings.TrimSpace(slug)}, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "工作区已创建: %s (%s)", response.Data.Name, response.Data.ID)
			output.Hint(f.IOStreams.Out, "使用 spaceagent tenant switch %s 切换", response.Data.ID)
			return nil
		},
	}
	cmd.Flags().StringVar(&name, "name", "", "工作区名称")
	cmd.Flags().StringVar(&slug, "slug", "", "URL 友好的唯一标识（可选）")
	return cmd
}

func newSwitchCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "switch <tenant-id-or-slug>",
		Short: "切换当前工作区并轮换令牌",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			tenantID, tenantName, err := resolveTenant(f, args[0])
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.AuthResponse]
			if err := c.Post("/api/v1/tenants/"+url.PathEscape(tenantID)+"/switch", nil, &response); err != nil {
				return err
			}
			cfg, err := f.Config()
			if err != nil {
				return err
			}
			applyAuth(cfg, response.Data)
			if err := config.Save(cfg); err != nil {
				return err
			}
			f.ReloadConfig()
			output.Success(f.IOStreams.Out, "已切换到工作区: %s", tenantName)
			output.Info(f.IOStreams.Out, "工作区角色: %s", response.Data.TenantRole)
			return nil
		},
	}
}

func newMembersCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "members [tenant-id-or-slug]",
		Short: "列出工作区成员",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			tenantID, _, err := currentOrResolvedTenant(f, args)
			if err != nil {
				return err
			}
			members, err := listMembers(f, tenantID)
			if err != nil {
				return err
			}
			table := output.NewTable(f.IOStreams.Out, []string{"用户 ID", "用户名", "显示名称", "角色", "状态", "加入时间"})
			for _, member := range members {
				table.Append([]string{output.TruncateID(member.UserID), member.Username, member.DisplayName, member.Role, member.Status, output.FormatTime(member.JoinedAt)})
			}
			table.Render()
			return nil
		},
	}
}

func newAddMemberCmd(f *factory.Factory) *cobra.Command {
	var tenantRef, role string
	cmd := &cobra.Command{
		Use:   "add-member <username>",
		Short: "向工作区添加成员",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			role, err := normalizeRole(role)
			if err != nil {
				return err
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.TenantMemberResponse]
			if err := c.Post(tenantMembersPath(tenantID), client.AddTenantMemberRequest{Username: args[0], Role: role}, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "已添加成员 %s，角色 %s", response.Data.Username, response.Data.Role)
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().StringVar(&role, "role", "MEMBER", "角色: ADMIN|MEMBER|VIEWER")
	return cmd
}

func newSetRoleCmd(f *factory.Factory) *cobra.Command {
	var tenantRef, role string
	cmd := &cobra.Command{
		Use:   "set-role <user-id>",
		Short: "修改成员角色",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			role, err := normalizeRole(role)
			if err != nil {
				return err
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			path := tenantMembersPath(tenantID) + "/" + url.PathEscape(args[0])
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.TenantMemberResponse]
			if err := c.Patch(path, client.UpdateTenantMemberRoleRequest{Role: role}, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "%s 的角色已更新为 %s", response.Data.Username, response.Data.Role)
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().StringVar(&role, "role", "", "角色: ADMIN|MEMBER|VIEWER")
	_ = cmd.MarkFlagRequired("role")
	return cmd
}

func newRemoveMemberCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	var yes bool
	cmd := &cobra.Command{
		Use:   "remove-member <user-id>",
		Short: "移除工作区成员",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !yes {
				return fmt.Errorf("该操作会立即撤销成员访问权限；确认后请添加 --yes")
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			path := tenantMembersPath(tenantID) + "/" + url.PathEscape(args[0])
			var response client.ApiResponse[any]
			if err := c.Delete(path, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "成员已移除")
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().BoolVar(&yes, "yes", false, "确认移除")
	return cmd
}

func newTransferOwnershipCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	var yes bool
	cmd := &cobra.Command{
		Use:   "transfer-owner <user-id>",
		Short: "将组织所有权转移给现有成员",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !yes {
				return fmt.Errorf("所有权转移后当前 OWNER 将变为 ADMIN；确认后请添加 --yes")
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.TenantMemberResponse]
			if err := c.Post(
				"/api/v1/tenants/"+url.PathEscape(tenantID)+"/transfer-ownership",
				client.TransferTenantOwnershipRequest{TargetUserID: args[0]},
				&response,
			); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "所有权已转移给 %s", response.Data.Username)
			output.Hint(f.IOStreams.Out, "当前租户令牌已撤销，请重新登录或切换工作区")
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().BoolVar(&yes, "yes", false, "确认转移")
	return cmd
}

func newLeaveCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	var yes bool
	cmd := &cobra.Command{
		Use:   "leave",
		Short: "退出组织工作区",
		RunE: func(cmd *cobra.Command, args []string) error {
			if !yes {
				return fmt.Errorf("退出后将立即失去组织访问权限；确认后请添加 --yes")
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			cfg, _ := f.Config()
			if cfg.TenantID == tenantID {
				return fmt.Errorf("请先切换到个人工作区，再退出当前组织")
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[any]
			if err := c.Post("/api/v1/tenants/"+url.PathEscape(tenantID)+"/leave", nil, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "已退出组织")
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "组织 ID 或 slug")
	cmd.Flags().BoolVar(&yes, "yes", false, "确认退出")
	return cmd
}

func newArchiveCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	var yes bool
	cmd := &cobra.Command{
		Use:   "archive",
		Short: "归档组织工作区",
		RunE: func(cmd *cobra.Command, args []string) error {
			if !yes {
				return fmt.Errorf("归档会撤销所有成员访问权限；确认后请添加 --yes")
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			cfg, _ := f.Config()
			if cfg.TenantID == tenantID {
				return fmt.Errorf("请先切换到个人工作区，再归档当前组织")
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[any]
			if err := c.Delete("/api/v1/tenants/"+url.PathEscape(tenantID), &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "组织已归档")
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "组织 ID 或 slug")
	cmd.Flags().BoolVar(&yes, "yes", false, "确认归档")
	return cmd
}

func newInviteCmd(f *factory.Factory) *cobra.Command {
	var tenantRef, role string
	cmd := &cobra.Command{
		Use:   "invite <username>",
		Short: "创建限时成员邀请",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			role, err := normalizeRole(role)
			if err != nil {
				return err
			}
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.TenantInvitationResponse]
			if err := c.Post(
				"/api/v1/tenants/"+url.PathEscape(tenantID)+"/invitations",
				client.CreateTenantInvitationRequest{Username: args[0], Role: role},
				&response,
			); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "邀请已创建，角色 %s，过期时间 %s", response.Data.Role, output.FormatTime(response.Data.ExpiresAt))
			output.Info(f.IOStreams.Out, "邀请令牌（仅显示一次）: %s", response.Data.Token)
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().StringVar(&role, "role", "MEMBER", "角色: ADMIN|MEMBER|VIEWER")
	return cmd
}

func newInvitationsCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	cmd := &cobra.Command{
		Use:   "invitations",
		Short: "列出组织发出的邀请",
		RunE: func(cmd *cobra.Command, args []string) error {
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			invitations, err := getInvitations(f, "/api/v1/tenants/"+url.PathEscape(tenantID)+"/invitations")
			if err != nil {
				return err
			}
			renderInvitations(f, invitations)
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	return cmd
}

func newIncomingInvitationsCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "incoming",
		Short: "列出收到的邀请",
		RunE: func(cmd *cobra.Command, args []string) error {
			invitations, err := getInvitations(f, "/api/v1/tenant-invitations")
			if err != nil {
				return err
			}
			renderInvitations(f, invitations)
			return nil
		},
	}
}

func newAcceptInvitationCmd(f *factory.Factory) *cobra.Command {
	return invitationResponseCmd(f, "accept <token>", "接受邀请", "/api/v1/tenant-invitations/accept")
}

func newDeclineInvitationCmd(f *factory.Factory) *cobra.Command {
	return invitationResponseCmd(f, "decline <token>", "拒绝邀请", "/api/v1/tenant-invitations/decline")
}

func invitationResponseCmd(f *factory.Factory, use, short, path string) *cobra.Command {
	return &cobra.Command{
		Use: use, Short: short, Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[client.TenantInvitationResponse]
			if err := c.Post(path, client.TenantInvitationTokenRequest{Token: args[0]}, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "%s成功: %s", short, response.Data.TenantName)
			return nil
		},
	}
}

func newRevokeInvitationCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	cmd := &cobra.Command{
		Use:   "revoke-invite <invitation-id>",
		Short: "撤销待接受邀请",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[any]
			path := "/api/v1/tenants/" + url.PathEscape(tenantID) + "/invitations/" + url.PathEscape(args[0])
			if err := c.Delete(path, &response); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "邀请已撤销")
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	return cmd
}

func newAuditCmd(f *factory.Factory) *cobra.Command {
	var tenantRef string
	var limit int
	cmd := &cobra.Command{
		Use:   "audit",
		Short: "查看租户治理审计日志",
		RunE: func(cmd *cobra.Command, args []string) error {
			tenantID, _, err := tenantFromFlag(f, tenantRef)
			if err != nil {
				return err
			}
			c, err := f.Client()
			if err != nil {
				return err
			}
			var response client.ApiResponse[[]client.TenantAuditResponse]
			params := url.Values{"limit": []string{fmt.Sprintf("%d", limit)}}
			if err := c.GetWithParams("/api/v1/tenants/"+url.PathEscape(tenantID)+"/audit", params, &response); err != nil {
				return err
			}
			table := output.NewTable(f.IOStreams.Out, []string{"时间", "动作", "操作者", "目标类型", "目标 ID", "Request ID"})
			for _, item := range response.Data {
				table.Append([]string{output.FormatTime(item.CreatedAt), item.Action, output.TruncateID(item.ActorUserID), item.TargetType, output.TruncateID(item.TargetID), item.RequestID})
			}
			table.Render()
			return nil
		},
	}
	cmd.Flags().StringVar(&tenantRef, "tenant", "", "工作区 ID 或 slug（默认当前工作区）")
	cmd.Flags().IntVar(&limit, "limit", 50, "最多返回条数（1-200）")
	return cmd
}

func getInvitations(f *factory.Factory, path string) ([]client.TenantInvitationResponse, error) {
	c, err := f.Client()
	if err != nil {
		return nil, err
	}
	var response client.ApiResponse[[]client.TenantInvitationResponse]
	if err := c.Get(path, &response); err != nil {
		return nil, err
	}
	return response.Data, nil
}

func renderInvitations(f *factory.Factory, invitations []client.TenantInvitationResponse) {
	table := output.NewTable(f.IOStreams.Out, []string{"ID", "工作区", "用户名", "角色", "状态", "过期时间"})
	for _, item := range invitations {
		table.Append([]string{output.TruncateID(item.ID), item.TenantName, item.InvitedUsername, item.Role, item.Status, output.FormatTime(item.ExpiresAt)})
	}
	table.Render()
}

func listTenants(f *factory.Factory) ([]client.TenantResponse, error) {
	c, err := f.Client()
	if err != nil {
		return nil, err
	}
	var response client.ApiResponse[[]client.TenantResponse]
	if err := c.Get("/api/v1/tenants", &response); err != nil {
		return nil, err
	}
	return response.Data, nil
}

func listMembers(f *factory.Factory, tenantID string) ([]client.TenantMemberResponse, error) {
	c, err := f.Client()
	if err != nil {
		return nil, err
	}
	var response client.ApiResponse[[]client.TenantMemberResponse]
	if err := c.Get(tenantMembersPath(tenantID), &response); err != nil {
		return nil, err
	}
	return response.Data, nil
}

func tenantMembersPath(tenantID string) string {
	return "/api/v1/tenants/" + url.PathEscape(tenantID) + "/members"
}

func currentOrResolvedTenant(f *factory.Factory, args []string) (string, string, error) {
	if len(args) == 1 {
		return resolveTenant(f, args[0])
	}
	return tenantFromFlag(f, "")
}

func tenantFromFlag(f *factory.Factory, tenantRef string) (string, string, error) {
	if strings.TrimSpace(tenantRef) != "" {
		return resolveTenant(f, tenantRef)
	}
	cfg, err := f.Config()
	if err != nil {
		return "", "", err
	}
	if cfg.TenantID == "" {
		return "", "", fmt.Errorf("当前凭据没有工作区信息，请重新登录")
	}
	return cfg.TenantID, cfg.TenantID, nil
}

func resolveTenant(f *factory.Factory, tenantRef string) (string, string, error) {
	tenantRef = strings.TrimSpace(tenantRef)
	tenants, err := listTenants(f)
	if err != nil {
		return "", "", err
	}
	for _, tenant := range tenants {
		if tenant.ID == tenantRef || strings.EqualFold(tenant.Slug, tenantRef) {
			return tenant.ID, tenant.Name, nil
		}
	}
	return "", "", fmt.Errorf("找不到工作区 %q", tenantRef)
}

func normalizeRole(role string) (string, error) {
	role = strings.ToUpper(strings.TrimSpace(role))
	if !validRoles[role] {
		return "", fmt.Errorf("角色必须是 ADMIN、MEMBER 或 VIEWER")
	}
	return role, nil
}

func applyAuth(cfg *config.Config, auth client.AuthResponse) {
	cfg.Token = auth.Token
	cfg.RefreshToken = auth.RefreshToken
	cfg.UserID = auth.UserID
	cfg.Username = auth.Username
	cfg.Role = auth.Role
	cfg.TenantID = auth.TenantID
	cfg.TenantRole = auth.TenantRole
}
