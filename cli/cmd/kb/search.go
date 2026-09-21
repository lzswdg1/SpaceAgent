package kb

import (
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewSearchCmd(f *factory.Factory) *cobra.Command {
	var documentIDs []string
	var topK int
	var threshold float64

	cmd := &cobra.Command{
		Use:   "search <query>",
		Short: "执行一次真实 RAG 检索",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			var configuredThreshold *float64
			if cmd.Flags().Changed("threshold") {
				configuredThreshold = &threshold
			}
			return runKBSearch(f, args[0], documentIDs, topK, configuredThreshold)
		},
	}

	cmd.Flags().StringSliceVarP(&documentIDs, "document", "d", nil, "限定文档 ID，可重复或用逗号分隔")
	cmd.Flags().IntVar(&topK, "top-k", 5, "返回的最大 chunk 数")
	cmd.Flags().Float64Var(&threshold, "threshold", 0, "覆盖默认相似度阈值（0-1）")
	return cmd
}

func runKBSearch(
	f *factory.Factory,
	query string,
	documentIDs []string,
	topK int,
	threshold *float64,
) error {
	out := f.IOStreams.Out
	query = strings.TrimSpace(query)
	if query == "" {
		return fmt.Errorf("检索问题不能为空")
	}
	if topK < 1 || topK > 20 {
		return fmt.Errorf("top-k 必须在 1 到 20 之间")
	}
	if threshold != nil && (*threshold < 0 || *threshold > 1) {
		return fmt.Errorf("threshold 必须在 0 到 1 之间")
	}

	c, err := f.Client()
	if err != nil {
		return err
	}

	s := output.StartSpinner(" 正在执行向量检索...")
	var resp client.ApiResponse[client.KBSearchResponse]
	err = c.Post("/api/v1/knowledge/retrieve", client.KBSearchRequest{
		Query:               query,
		KnowledgeBaseIDs:    documentIDs,
		TopK:                topK,
		SimilarityThreshold: threshold,
	}, &resp)
	output.StopSpinner(s)
	if err != nil {
		output.Error(out, "知识库检索失败: %s", err)
		return err
	}

	if len(resp.Data.Chunks) == 0 {
		output.Info(out, "没有召回达到阈值的 chunk")
		return nil
	}

	table := output.NewTable(out, []string{"来源", "综合分", "向量分", "词项分", "内容"})
	for _, hit := range resp.Data.Chunks {
		source := hit.Citation
		if source == "" {
			source = fmt.Sprintf("%s#chunk-%d", hit.DocumentName, hit.ChunkIndex)
		}
		table.Append([]string{
			output.TruncateString(source, 34),
			fmt.Sprintf("%.4f", hit.Score),
			fmt.Sprintf("%.4f", hit.VectorScore),
			fmt.Sprintf("%.4f", hit.LexicalScore),
			output.TruncateString(strings.Join(strings.Fields(hit.Content), " "), 72),
		})
	}
	table.Render()
	fmt.Fprintf(out, "  共召回 %d 个 chunk\n\n", len(resp.Data.Chunks))
	return nil
}
