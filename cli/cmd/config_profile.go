package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func newProfileCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "profile",
		Short: "管理隔离的服务器与登录配置",
		Long: "命名 Profile 隔离 Gateway 地址和登录令牌。\n" +
			"选择优先级: --profile > SPACEAGENT_PROFILE > 当前 Profile > default。",
	}
	cmd.AddCommand(
		newProfileListCmd(f),
		newProfileAddCmd(f),
		newProfileUseCmd(f),
		newProfileRemoveCmd(f),
		newProfileShowCmd(f),
	)
	return cmd
}

func newProfileListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "列出所有 Profile",
		RunE: func(cmd *cobra.Command, args []string) error {
			profiles, err := config.ListProfiles()
			if err != nil {
				return err
			}
			table := output.NewTable(f.IOStreams.Out, []string{"当前", "名称", "服务器", "用户", "状态"})
			for _, profile := range profiles {
				current := ""
				if profile.Active {
					current = "*"
				}
				username := profile.Username
				if username == "" {
					username = "-"
				}
				status := "未登录"
				if profile.LoggedIn {
					status = "已登录"
				}
				table.Append([]string{current, profile.Name, profile.Server, username, status})
			}
			table.Render()
			return nil
		},
	}
}

func newProfileAddCmd(f *factory.Factory) *cobra.Command {
	var server string
	var activate bool
	cmd := &cobra.Command{
		Use:   "add <name>",
		Short: "创建 Profile",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := config.CreateProfile(args[0], server)
			if err != nil {
				return err
			}
			if activate {
				if err := config.SetActiveProfile(cfg.Profile); err != nil {
					return err
				}
			}
			output.Success(f.IOStreams.Out, "Profile 已创建: %s", cfg.Profile)
			output.Hint(f.IOStreams.Out, "使用 spaceagent --profile %s auth login 登录", cfg.Profile)
			return nil
		},
	}
	cmd.Flags().StringVar(&server, "server", config.DefaultServer, "Gateway 地址")
	cmd.Flags().BoolVar(&activate, "use", false, "创建后设为默认 Profile")
	return cmd
}

func newProfileUseCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "use <name>",
		Short: "切换默认 Profile",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := config.SetActiveProfile(args[0]); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "当前 Profile: %s", args[0])
			return nil
		},
	}
}

func newProfileRemoveCmd(f *factory.Factory) *cobra.Command {
	var yes bool
	cmd := &cobra.Command{
		Use:   "remove <name>",
		Short: "删除 Profile",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !yes {
				return fmt.Errorf("删除 Profile 会清除其中的登录令牌；确认后请添加 --yes")
			}
			if err := config.RemoveProfile(args[0]); err != nil {
				return err
			}
			output.Success(f.IOStreams.Out, "Profile 已删除: %s", args[0])
			return nil
		},
	}
	cmd.Flags().BoolVar(&yes, "yes", false, "确认删除")
	return cmd
}

func newProfileShowCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "show",
		Short: "显示当前解析后的 Profile",
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg, err := f.Config()
			if err != nil {
				return err
			}
			fmt.Fprintf(f.IOStreams.Out, "Profile: %s\n", cfg.Profile)
			fmt.Fprintf(f.IOStreams.Out, "Server:  %s\n", cfg.Server)
			if cfg.Username == "" {
				fmt.Fprintln(f.IOStreams.Out, "User:    (未登录)")
			} else {
				fmt.Fprintf(f.IOStreams.Out, "User:    %s (%s)\n", cfg.Username, cfg.Role)
			}
			return nil
		},
	}
}
