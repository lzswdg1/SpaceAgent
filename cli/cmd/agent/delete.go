package agent

import (
	"fmt"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewDeleteCmd 创建 Agent 删除命令。
func NewDeleteCmd(f *factory.Factory) *cobra.Command {
	var yes bool
	cmd := &cobra.Command{
		Use:   "delete <id>",
		Short: "删除 Agent",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runDelete(f, args[0], yes)
		},
	}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "跳过确认提示")
	return cmd
}

func runDelete(f *factory.Factory, id string, yes bool) error {
	out := f.IOStreams.Out

	// 确认删除
	if !yes {
		var confirmed bool
		prompt := &survey.Confirm{
			Message: fmt.Sprintf("确定要删除 Agent %s 吗？此操作不可撤销", id),
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
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	// 后端返回 204 No Content，pass nil as result
	err = c.Delete(fmt.Sprintf("/api/v1/agents/%s", id), nil)
	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "Agent 不存在: %s", id)
			case 403:
				output.Error(out, "无权删除此 Agent")
			default:
				output.Error(out, "删除失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	output.Success(out, "Agent 已删除: %s", id)
	return nil
}
