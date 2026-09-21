package kb

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
)

func TestRunKBSearchUsesPublicKnowledgeRetrieveContract(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/knowledge/retrieve" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		var request client.KBSearchRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		if request.Query != "SpaceAgent RAG" || request.TopK != 3 {
			t.Fatalf("unexpected search request: %+v", request)
		}
		if len(request.KnowledgeBaseIDs) != 1 || request.KnowledgeBaseIDs[0] != "doc-1" {
			t.Fatalf("unexpected document filter: %+v", request.KnowledgeBaseIDs)
		}
		if request.SimilarityThreshold == nil || *request.SimilarityThreshold != 0.6 {
			t.Fatalf("unexpected threshold: %+v", request.SimilarityThreshold)
		}
		writeJSON(t, w, map[string]interface{}{
			"success": true,
			"data": map[string]interface{}{
				"chunks": []map[string]interface{}{{
					"documentId": "doc-1", "chunkId": "chunk-1", "content": "SpaceAgent supports RAG",
					"score": 0.91, "vectorScore": 0.9, "lexicalScore": 1.0,
					"citation": "demo.txt#chunk-0", "documentName": "demo.txt", "chunkIndex": 0,
				}},
			},
			"message": "OK",
		})
	}))
	defer server.Close()

	var out bytes.Buffer
	f := commandTestFactory(server.URL, &out)
	threshold := 0.6
	if err := runKBSearch(f, "SpaceAgent RAG", []string{"doc-1"}, 3, &threshold); err != nil {
		t.Fatalf("runKBSearch returned error: %v", err)
	}
	if !strings.Contains(out.String(), "demo.txt#chunk-0") || !strings.Contains(out.String(), "0.9100") {
		t.Fatalf("unexpected output: %q", out.String())
	}
}

func TestRunKBServiceDiagnosticsUsesDiagnosticsEndpoint(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/v1/knowledge/diagnostics" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		writeJSON(t, w, map[string]interface{}{
			"success": true,
			"data": map[string]interface{}{
				"documentCount": 2, "completedDocumentCount": 1, "failedDocumentCount": 0,
				"processingDocumentCount": 1, "chunkCount": 3, "embeddingConfigured": true,
				"embeddingModel": "text-embedding-v3", "configuredEmbeddingDimensions": 1024,
				"storedEmbeddingDimensions": 1024, "maxChunkSize": 512, "overlapSize": 50,
				"defaultTopK": 5, "similarityThreshold": 0.55, "rerankEnabled": true,
				"rerankCandidateMultiplier": 4, "rerankMaxCandidates": 80,
				"rerankVectorWeight": 0.85, "rerankLexicalWeight": 0.15,
				"embeddingBatchSize": 10, "embeddingCacheEnabled": true,
				"embeddingCacheMaximumSize": 1000, "embeddingCacheTtlSeconds": 600,
				"hnswEfSearch": 100, "ingestionPersistenceBatchSize": 100,
			},
			"message": "OK",
		})
	}))
	defer server.Close()

	var out bytes.Buffer
	if err := runKBServiceDiagnostics(commandTestFactory(server.URL, &out)); err != nil {
		t.Fatalf("runKBServiceDiagnostics returned error: %v", err)
	}
	if !strings.Contains(out.String(), "text-embedding-v3") || !strings.Contains(out.String(), "配置 1024 / 入库 1024") {
		t.Fatalf("unexpected output: %q", out.String())
	}
	if !strings.Contains(out.String(), "embedding batch 10") || !strings.Contains(out.String(), "HNSW ef 100") {
		t.Fatalf("missing RAG performance diagnostics: %q", out.String())
	}
}

func commandTestFactory(serverURL string, out *bytes.Buffer) *factory.Factory {
	c := client.New(serverURL, "test-token")
	return &factory.Factory{
		ClientFunc: func() (*client.Client, error) { return c, nil },
		IOStreams:  &factory.IOStreams{In: bytes.NewReader(nil), Out: out, ErrOut: io.Discard},
	}
}

func writeJSON(t *testing.T, w http.ResponseWriter, value interface{}) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		t.Fatalf("encode response: %v", err)
	}
}
