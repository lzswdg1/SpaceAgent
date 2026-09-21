package cmd

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
)

// Version information (injected via ldflags at build time)
var (
	version = "dev"
	commit  = "none"
	date    = "unknown"
)

func NewVersionCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "显示 CLI 版本信息",
		Run: func(cmd *cobra.Command, args []string) {
			fmt.Fprintf(f.IOStreams.Out, "spaceagent %s (commit: %s, built: %s)\n", version, commit, date)
		},
	}
}
