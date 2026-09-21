// retry.go implements manual replay for failed knowledge ingestion.
//
// Usage: spaceagent kb retry <documentId>
package kb

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewRetryCmd creates the failed-document retry command.
func NewRetryCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "retry <documentId>",
		Short: "重试失败的文档处理任务",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKBRetry(f, args[0])
		},
	}
}

func runKBRetry(f *factory.Factory, documentID string) error {
	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 正在重新提交文档...")
	var response client.ApiResponse[client.KBDocumentResponse]
	err = c.Post(
		fmt.Sprintf("/api/v1/knowledge/documents/%s/retry", documentID),
		map[string]interface{}{},
		&response,
	)
	output.StopSpinner(s)
	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(f.IOStreams.Out, "重试失败: %s", apiErr.Msg)
		} else {
			output.Error(f.IOStreams.Out, "请求失败: %s", err)
		}
		return err
	}

	output.Success(
		f.IOStreams.Out,
		"文档已重新提交: %s（状态: %s）",
		response.Data.FileName,
		response.Data.Status,
	)
	return nil
}
