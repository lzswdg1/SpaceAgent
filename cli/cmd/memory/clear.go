// clear.go 实现了记忆清除命令。
//
// 用法: spaceagent memory clear
package memory

import (
	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewClearCmd 创建记忆清除命令。
func NewClearCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "clear",
		Short: "清除所有记忆",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runMemoryClear(f)
		},
	}
}

func runMemoryClear(f *factory.Factory) error {
	out := f.IOStreams.Out

	// 确认清除
	var confirmed bool
	prompt := &survey.Confirm{
		Message: "确定要清除所有记忆吗？此操作不可撤销",
		Default: false,
	}
	if err := survey.AskOne(prompt, &confirmed); err != nil {
		output.Error(out, "操作已取消")
		return nil
	}

	if !confirmed {
		output.Error(out, "操作已取消")
		return nil
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	err = c.Delete("/api/v1/memory", nil)
	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "清除记忆失败: %s", apiErr.Msg)
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	output.Success(out, "记忆已清除")
	return nil
}
