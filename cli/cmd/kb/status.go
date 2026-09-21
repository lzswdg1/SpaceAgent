// status.go 实现了知识库文档状态查询命令。
//
// 用法: spaceagent kb status <documentId>
package kb

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewStatusCmd 创建知识库文档状态查询命令。
func NewStatusCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "status <documentId>",
		Short: "查看文档处理状态",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKBStatus(f, args[0])
		},
	}
}

func runKBStatus(f *factory.Factory, docID string) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 查询文档状态...")
	var resp client.ApiResponse[client.KBDocumentResponse]
	err = c.Get(fmt.Sprintf("/api/v1/knowledge/documents/%s", docID), &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			switch apiErr.StatusCode {
			case 404:
				output.Error(out, "文档不存在: %s", docID)
			default:
				output.Error(out, "查询失败: %s", apiErr.Msg)
			}
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	doc := resp.Data
	// 详细展示文档信息
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  文档ID:      %s\n", doc.ID)
	fmt.Fprintf(out, "  文件名:      %s\n", doc.FileName)
	fmt.Fprintf(out, "  内容类型:    %s\n", doc.ContentType)
	fmt.Fprintf(out, "  文件大小:    %s\n", formatKBFileSize(doc.FileSize))
	fmt.Fprintf(out, "  分块数量:    %d\n", doc.ChunkCount)
	fmt.Fprintf(out, "  处理状态:    %s\n", doc.Status)
	if doc.ErrorReason != "" {
		fmt.Fprintf(out, "  错误原因:    %s\n", doc.ErrorReason)
	}
	fmt.Fprintf(out, "  创建时间:    %s\n", output.FormatTime(doc.CreatedAt))
	if doc.ProcessedAt != "" {
		fmt.Fprintf(out, "  处理时间:    %s\n", output.FormatTime(doc.ProcessedAt))
	}
	fmt.Fprintln(out)

	return nil
}
