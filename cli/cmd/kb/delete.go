// delete.go 实现了知识库文档删除命令。
//
// 用法: spaceagent kb delete <documentId>
package kb

import (
	"fmt"

	"github.com/AlecAivazis/survey/v2"
	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewKBDeleteCmd 创建知识库文档删除命令。
func NewKBDeleteCmd(f *factory.Factory) *cobra.Command {
	var yes bool

	cmd := &cobra.Command{
		Use:   "delete <documentId>",
		Short: "删除文档",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKBDelete(f, args[0], yes)
		},
	}

	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "跳过确认提示")
	return cmd
}

func runKBDelete(f *factory.Factory, docID string, yes bool) error {
	out := f.IOStreams.Out

	// 确认删除
	if !yes {
		var confirmed bool
		prompt := &survey.Confirm{
			Message: fmt.Sprintf("确定要删除文档 %s 吗？此操作不可撤销", docID),
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

	s := output.StartSpinner(" 正在删除...")
	err = c.Delete(fmt.Sprintf("/api/v1/knowledge/documents/%s", docID), nil)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "文档不存在: %s", docID)
			default:
				output.Error(out, "删除失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	output.Success(out, "文档已删除: %s", docID)
	return nil
}
