package kb

import (
	"fmt"

	"github.com/spf13/cobra"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
	"github.com/lzswdg1/SpaceAgent/cli/internal/output"
)

func NewDiagnosticsCmd(f *factory.Factory) *cobra.Command {
	return &cobra.Command{
		Use:   "diagnostics [document-id]",
		Short: "检查知识库或单个文档的摄取状态",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) == 1 {
				return runKBDocumentDiagnostics(f, args[0])
			}
			return runKBServiceDiagnostics(f)
		},
	}
}

func runKBServiceDiagnostics(f *factory.Factory) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	var resp client.ApiResponse[client.KBServiceDiagnosticsResponse]
	if err := c.Get("/api/v1/knowledge/diagnostics", &resp); err != nil {
		output.Error(out, "知识库诊断失败: %s", err)
		return err
	}

	d := resp.Data
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  文档:       %d（完成 %d / 处理中 %d / 失败 %d）\n",
		d.DocumentCount, d.CompletedDocumentCount, d.ProcessingDocumentCount, d.FailedDocumentCount)
	fmt.Fprintf(out, "  Chunk:      %d\n", d.ChunkCount)
	fmt.Fprintf(out, "  Embedding:  %s / %s\n", configuredLabel(d.EmbeddingConfigured), d.EmbeddingModel)
	fmt.Fprintf(out, "  向量维度:   配置 %d / 入库 %s\n",
		d.ConfiguredEmbeddingDimensions, optionalInt(d.StoredEmbeddingDimensions))
	fmt.Fprintf(out, "  切分参数:   chunk %d / overlap %d\n", d.MaxChunkSize, d.OverlapSize)
	fmt.Fprintf(out, "  检索参数:   TopK %d / threshold %.2f / rerank %s\n",
		d.DefaultTopK, d.SimilarityThreshold, configuredLabel(d.RerankEnabled))
	if d.RerankEnabled {
		fmt.Fprintf(out, "  重排参数:   候选 x%d（上限 %d）/ vector %.2f / lexical %.2f\n",
			d.RerankCandidateMultiplier, d.RerankMaxCandidates,
			d.RerankVectorWeight, d.RerankLexicalWeight)
	}
	fmt.Fprintf(out, "  性能参数:   embedding batch %d / cache %s（%d, %ds）/ HNSW ef %d / DB batch %d\n",
		d.EmbeddingBatchSize, configuredLabel(d.EmbeddingCacheEnabled),
		d.EmbeddingCacheMaximumSize, d.EmbeddingCacheTTLSeconds,
		d.HNSWEfSearch, d.IngestionPersistenceBatchSize)
	fmt.Fprintln(out)
	return nil
}

func runKBDocumentDiagnostics(f *factory.Factory, documentID string) error {
	out := f.IOStreams.Out
	c, err := f.Client()
	if err != nil {
		return err
	}

	var resp client.ApiResponse[client.KBDocumentDiagnosticsResponse]
	path := fmt.Sprintf("/api/v1/knowledge/documents/%s/diagnostics", documentID)
	if err := c.Get(path, &resp); err != nil {
		output.Error(out, "文档诊断失败: %s", err)
		return err
	}

	d := resp.Data
	fmt.Fprintln(out)
	fmt.Fprintf(out, "  文档:       %s (%s)\n", d.Document.FileName, d.Document.ID)
	fmt.Fprintf(out, "  状态:       %s\n", d.Document.Status)
	fmt.Fprintf(out, "  Chunk:      声明 %d / 入库 %d\n", d.Document.ChunkCount, d.StoredChunkCount)
	fmt.Fprintf(out, "  Chunk 长度: min %s / max %s / avg %s\n",
		optionalInt(d.MinChunkLength), optionalInt(d.MaxChunkLength), optionalFloat(d.AvgChunkLength))
	fmt.Fprintf(out, "  向量维度:   %s\n", optionalInt(d.EmbeddingDimensions))

	if len(d.SampleChunks) > 0 {
		fmt.Fprintln(out)
		table := output.NewTable(out, []string{"索引", "字符范围", "长度", "维度", "预览"})
		for _, chunk := range d.SampleChunks {
			table.Append([]string{
				fmt.Sprintf("%d", chunk.ChunkIndex),
				fmt.Sprintf("%d-%d", chunk.StartOffset, chunk.EndOffset),
				fmt.Sprintf("%d", chunk.ContentLength),
				optionalInt(chunk.EmbeddingDimensions),
				output.TruncateString(chunk.ContentPreview, 64),
			})
		}
		table.Render()
	}
	fmt.Fprintln(out)
	return nil
}

func configuredLabel(configured bool) string {
	if configured {
		return "已启用"
	}
	return "未启用"
}

func optionalInt(value *int) string {
	if value == nil {
		return "-"
	}
	return fmt.Sprintf("%d", *value)
}

func optionalFloat(value *float64) string {
	if value == nil {
		return "-"
	}
	return fmt.Sprintf("%.1f", *value)
}
