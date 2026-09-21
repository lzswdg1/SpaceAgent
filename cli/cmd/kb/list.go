// list.go 实现了知识库文档列表命令。
//
// 用法: spaceagent kb list
package kb

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewKBListCmd 创建知识库文档列表命令。
func NewKBListCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "查看文档列表",
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKBList(f)
		},
	}
}

func runKBList(f *factory.Factory) error {
	out := f.IOStreams.Out

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 加载文档列表...")
	var resp client.ApiResponse[[]client.KBDocumentResponse]
	err = c.Get("/api/v1/knowledge/documents", &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "获取文档列表失败: %s", apiErr.Msg)
		} else {
			output.Error(out, "请求失败: %s", err)
		}
		return err
	}

	docs := resp.Data
	if len(docs) == 0 {
		output.Info(out, "暂无知识库文档")
		return nil
	}

	// 表格展示
	table := output.NewTable(out, []string{"ID", "文件名", "状态", "大小", "创建时间"})
	for _, doc := range docs {
		table.Append([]string{
			output.TruncateID(doc.ID),
			output.TruncateString(doc.FileName, 30),
			doc.Status,
			formatKBFileSize(doc.FileSize),
			output.FormatTime(doc.CreatedAt),
		})
	}
	table.Render()

	fmt.Fprintf(out, "  共 %d 个文档\n\n", len(docs))
	return nil
}

// formatKBFileSize 将字节数转为人类可读的文件大小。
func formatKBFileSize(size int64) string {
	const (
		KB = 1024
		MB = KB * 1024
		GB = MB * 1024
	)
	switch {
	case size >= GB:
		return fmt.Sprintf("%.2f GB", float64(size)/float64(GB))
	case size >= MB:
		return fmt.Sprintf("%.2f MB", float64(size)/float64(MB))
	case size >= KB:
		return fmt.Sprintf("%.2f KB", float64(size)/float64(KB))
	default:
		return fmt.Sprintf("%d B", size)
	}
}
