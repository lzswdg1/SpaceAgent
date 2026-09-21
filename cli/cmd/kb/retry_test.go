package kb

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRunKBRetryUsesRetryEndpoint(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/knowledge/documents/doc-1/retry" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		writeJSON(t, w, map[string]interface{}{
			"success": true,
			"data": map[string]interface{}{
				"documentId": "doc-1",
				"fileName":   "failed.pdf",
				"status":     "PROCESSING",
			},
			"message": "OK",
		})
	}))
	defer server.Close()

	var out bytes.Buffer
	if err := runKBRetry(commandTestFactory(server.URL, &out), "doc-1"); err != nil {
		t.Fatalf("runKBRetry returned error: %v", err)
	}
	if !strings.Contains(out.String(), "failed.pdf") || !strings.Contains(out.String(), "PROCESSING") {
		t.Fatalf("unexpected output: %q", out.String())
	}
}
