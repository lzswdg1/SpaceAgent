package output

import (
	"fmt"
	"io"

	"github.com/fatih/color"
)

// RAGSummary 显示本轮真实知识库召回数量和引用来源。
func RAGSummary(out io.Writer, used bool, retrievedChunkCount int, citations []string) {
	if !used {
		return
	}

	fmt.Fprintf(out, "  %s %s\n",
		color.GreenString("•"),
		color.HiBlackString("RAG 命中 %d 个 chunk", retrievedChunkCount))

	seen := make(map[string]struct{}, len(citations))
	for _, citation := range citations {
		if citation == "" {
			continue
		}
		if _, exists := seen[citation]; exists {
			continue
		}
		seen[citation] = struct{}{}
		fmt.Fprintf(out, "    %s %s\n", color.CyanString("引用"), citation)
	}
}
