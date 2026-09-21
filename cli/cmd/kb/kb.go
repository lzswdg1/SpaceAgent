// Package kb 实现了知识库管理相关的命令。
//
// 命令：
//   - spaceagent kb upload      上传知识库文档
//   - spaceagent kb list        查看文档列表
//   - spaceagent kb status      查看文档处理状态
//   - spaceagent kb retry       重试失败文档
//   - spaceagent kb delete      删除文档
package kb

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

// NewKBCmd 创建知识库命令组。
func NewKBCmd(f *factory.Factory) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "kb",
		Short: "知识库管理",
		Long:  "管理知识库文档，并直接验证解析、向量化和 RAG 检索结果。",
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
		NewUploadCmd(f),
		NewKBListCmd(f),
		NewStatusCmd(f),
		NewRetryCmd(f),
		NewSearchCmd(f),
		NewDiagnosticsCmd(f),
		NewKBDeleteCmd(f),
	)

	return cmd
}
