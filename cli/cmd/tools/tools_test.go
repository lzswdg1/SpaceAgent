package tools

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

func TestRunToolListUsesToolCatalogEndpoint(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/v1/tools/catalog" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"success": true,
			"data": []map[string]interface{}{{
				"id": "time", "name": "Current Time", "description": "Returns current time",
				"category": "context", "configurable": false, "enabledByDefault": true,
			}},
		})
	}))
	defer server.Close()

	var out bytes.Buffer
	c := client.New(server.URL, "test-token")
	f := &factory.Factory{
		ClientFunc: func() (*client.Client, error) { return c, nil },
		IOStreams:  &factory.IOStreams{In: bytes.NewReader(nil), Out: &out, ErrOut: io.Discard},
	}
	if err := runToolList(f); err != nil {
		t.Fatalf("runToolList returned error: %v", err)
	}
	if !strings.Contains(out.String(), "Current Time") || !strings.Contains(out.String(), "context") {
		t.Fatalf("unexpected output: %q", out.String())
	}
}
