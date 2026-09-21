package admin

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

func TestRunUpdateProviderMergesExistingFieldsAndPreservesAPIKey(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/model-providers":
			providerTestJSON(t, w, map[string]interface{}{
				"success": true,
				"data": []map[string]interface{}{{
					"id": "provider-1", "name": "Qwen", "type": "openai-compatible",
					"baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
					"apiKey":  "configured", "authType": "bearer", "enabled": true, "isDefault": false,
				}},
			})
		case r.Method == http.MethodPut && r.URL.Path == "/api/v1/model-providers/provider-1":
			var request map[string]interface{}
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				t.Fatalf("decode request: %v", err)
			}
			if request["name"] != "Qwen Updated" || request["apiKey"] != "" || request["enabled"] != true {
				t.Fatalf("unexpected update payload: %+v", request)
			}
			providerTestJSON(t, w, map[string]interface{}{
				"success": true,
				"data": map[string]interface{}{
					"id": "provider-1", "name": "Qwen Updated", "type": "openai-compatible",
					"baseUrl": request["baseUrl"], "apiKey": "configured", "authType": "bearer",
					"enabled": true, "isDefault": false,
				},
			})
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()

	var out bytes.Buffer
	f := providerTestFactory(server.URL, &out)
	if err := runUpdateProvider(f, "provider-1", providerUpdateOptions{
		Name: "Qwen Updated", NameChanged: true,
	}); err != nil {
		t.Fatalf("runUpdateProvider returned error: %v", err)
	}
	if !strings.Contains(out.String(), "Qwen Updated") {
		t.Fatalf("unexpected output: %q", out.String())
	}
}

func TestRunDeleteProviderUsesDeleteEndpoint(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete || r.URL.Path != "/api/v1/model-providers/provider-1" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		providerTestJSON(t, w, map[string]interface{}{"success": true, "message": "OK"})
	}))
	defer server.Close()

	var out bytes.Buffer
	if err := runDeleteProvider(providerTestFactory(server.URL, &out), "provider-1", true); err != nil {
		t.Fatalf("runDeleteProvider returned error: %v", err)
	}
	if !strings.Contains(out.String(), "provider-1") {
		t.Fatalf("unexpected output: %q", out.String())
	}
}

func TestNonInteractiveProviderCreateAndModelAddUseBackendDTOs(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/model-providers":
			var request map[string]interface{}
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				t.Fatalf("decode provider request: %v", err)
			}
			if request["name"] != "DeepSeek" || request["type"] != "openai-compatible" || request["authType"] != "bearer" {
				t.Fatalf("unexpected provider request: %+v", request)
			}
			providerTestJSON(t, w, map[string]interface{}{
				"success": true,
				"data": map[string]interface{}{
					"id": "provider-2", "name": "DeepSeek", "type": "openai-compatible",
					"baseUrl": request["baseUrl"], "apiKey": "configured", "authType": "bearer", "enabled": true,
				},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/model-providers/provider-2/models":
			var request map[string]interface{}
			if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
				t.Fatalf("decode model request: %v", err)
			}
			if request["modelId"] != "deepseek-chat" || request["maxContextTokens"] != float64(65536) {
				t.Fatalf("unexpected model request: %+v", request)
			}
			providerTestJSON(t, w, map[string]interface{}{
				"success": true,
				"data": map[string]interface{}{
					"id": "model-1", "providerId": "provider-2", "modelId": "deepseek-chat",
					"displayName": "DeepSeek Chat", "maxContextTokens": 65536,
				},
			})
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()

	var out bytes.Buffer
	f := providerTestFactory(server.URL, &out)
	if err := runCreateProvider(
		f, "DeepSeek", "openai-compatible", "https://api.deepseek.com", "sk-test", "bearer", false,
	); err != nil {
		t.Fatalf("runCreateProvider returned error: %v", err)
	}
	if err := runCreateModel(f, "provider-2", "deepseek-chat", "DeepSeek Chat", 65536, false); err != nil {
		t.Fatalf("runCreateModel returned error: %v", err)
	}
	if !strings.Contains(out.String(), "DeepSeek Chat") {
		t.Fatalf("unexpected output: %q", out.String())
	}
}

func TestAddProviderCommandReadsAPIKeyFromEnvironment(t *testing.T) {
	t.Setenv("SPACEAGENT_PROVIDER_API_KEY", "sk-from-env")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request map[string]interface{}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatalf("decode provider request: %v", err)
		}
		if request["apiKey"] != "sk-from-env" {
			t.Fatalf("expected API key from environment, got %+v", request)
		}
		providerTestJSON(t, w, map[string]interface{}{
			"success": true,
			"data": map[string]interface{}{
				"id": "provider-env", "name": "Qwen", "type": "openai-compatible",
				"baseUrl": request["baseUrl"], "apiKey": "configured", "authType": "bearer", "enabled": true,
			},
		})
	}))
	defer server.Close()

	var out bytes.Buffer
	cmd := NewAddProviderCmd(providerTestFactory(server.URL, &out))
	cmd.SetArgs([]string{"--name", "Qwen", "--base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1"})
	if err := cmd.Execute(); err != nil {
		t.Fatalf("provider add command returned error: %v", err)
	}
}

func providerTestFactory(serverURL string, out *bytes.Buffer) *factory.Factory {
	c := client.New(serverURL, "test-token")
	return &factory.Factory{
		ClientFunc: func() (*client.Client, error) { return c, nil },
		IOStreams:  &factory.IOStreams{In: bytes.NewReader(nil), Out: out, ErrOut: io.Discard},
	}
}

func providerTestJSON(t *testing.T, w http.ResponseWriter, value interface{}) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		t.Fatalf("encode response: %v", err)
	}
}
