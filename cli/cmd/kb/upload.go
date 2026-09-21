// upload.go 实现了知识库文档上传命令。
//
// 用法: spaceagent kb upload <文件路径>
package kb

import (
	"bytes"
	"fmt"
	"io"
	"mime/multipart"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewUploadCmd 创建知识库文档上传命令。
func NewUploadCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "upload <文件路径>",
		Short: "上传知识库文档",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runKBUpload(f, args[0])
		},
	}
}

func runKBUpload(f *factory.Factory, filePath string) error {
	out := f.IOStreams.Out

	// 检查文件是否存在
	if _, err := os.Stat(filePath); err != nil {
		output.Error(out, "文件不存在: %s", filePath)
		return nil
	}

	// 打开文件
	file, err := os.Open(filePath)
	if err != nil {
		output.Error(out, "无法打开文件: %s", err)
		return nil
	}
	defer file.Close()

	// 构建 multipart/form-data 请求体
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile("file", filepath.Base(filePath))
	if err != nil {
		output.Error(out, "构建上传请求失败: %s", err)
		return nil
	}
	if _, err := io.Copy(part, file); err != nil {
		output.Error(out, "读取文件失败: %s", err)
		return nil
	}
	writer.Close()

	// 获取客户端
	c, err := f.Client()
	if err != nil {
		output.Error(out, "初始化客户端失败: %s", err)
		return nil
	}

	// 上传文件
	s := output.StartSpinner(" 正在上传...")
	var resp client.ApiResponse[client.KBDocumentResponse]
	err = c.PostRaw("/api/v1/knowledge/documents", &buf, writer.FormDataContentType(), &resp)
	output.StopSpinner(s)

	if err != nil {
		if apiErr, ok := err.(*client.APIError); ok {
			output.Error(out, "上传失败: %s", apiErr.Msg)
		} else {
			output.Error(out, "上传失败: %s", err)
		}
		return nil
	}

	result := resp.Data
	// 显示上传结果
	output.Success(out, "知识库文档上传成功")
	fmt.Fprintf(out, "  文档ID:    %s\n", result.ID)
	fmt.Fprintf(out, "  文件名:    %s\n", result.FileName)
	fmt.Fprintf(out, "  状态:      %s\n", result.Status)

	return nil
}
