package output

import (
	"bytes"
	"strings"
	"testing"
)

func TestRAGSummaryShowsHitsAndDeduplicatedCitations(t *testing.T) {
	var out bytes.Buffer

	RAGSummary(&out, true, 3, []string{"README.md#chunk-0", "README.md#chunk-0", "design.md#chunk-2"})

	text := out.String()
	if !strings.Contains(text, "RAG 命中 3 个 chunk") {
		t.Fatalf("expected hit count, got %q", text)
	}
	if strings.Count(text, "README.md#chunk-0") != 1 {
		t.Fatalf("expected citation to be deduplicated, got %q", text)
	}
	if !strings.Contains(text, "design.md#chunk-2") {
		t.Fatalf("expected second citation, got %q", text)
	}
}

func TestRAGSummaryDoesNothingWhenRagWasNotUsed(t *testing.T) {
	var out bytes.Buffer

	RAGSummary(&out, false, 0, nil)

	if out.Len() != 0 {
		t.Fatalf("expected no output, got %q", out.String())
	}
}
