package project

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
)

func TestBridgeRegisterKeepsAbsolutePathOutOfHTTPPayload(t *testing.T) {
	root := t.TempDir()
	configDir := t.TempDir()
	t.Setenv("SPACEAGENT_CONFIG_DIR", configDir)
	var payload map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/workspace-bridges" {
			t.Fatalf("path: %s", r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true, "message": "OK",
			"data": map[string]any{
				"bridgeToken": "brg_secret",
				"bridge": map[string]any{
					"id": "bridge-1", "displayName": "Demo", "deviceId": "device-1",
					"rootHandle": payload["rootHandle"], "tokenPrefix": "brg_secret", "state": "ACTIVE",
				},
			},
		})
	}))
	defer server.Close()

	cfg := &config.Config{Profile: "default", Server: server.URL, Token: "jwt", UserID: "user-1", TenantID: "tenant-1"}
	f := &factory.Factory{
		ConfigFunc: func() (*config.Config, error) { return cfg, nil },
		ClientFunc: func() (*client.Client, error) { return client.New(server.URL, "jwt"), nil },
		IOStreams:  &factory.IOStreams{In: bytes.NewBuffer(nil), Out: bytes.NewBuffer(nil), ErrOut: bytes.NewBuffer(nil)},
	}
	cmd := NewProjectCmd(f)
	cmd.SetArgs([]string{"bridge", "register", root, "--name", "Demo", "--device", "device-1"})
	if err := cmd.Execute(); err != nil {
		t.Fatal(err)
	}
	if _, exists := payload["path"]; exists {
		t.Fatalf("path leaked: %#v", payload)
	}
	if _, exists := payload["localPath"]; exists {
		t.Fatalf("localPath leaked: %#v", payload)
	}
	if _, exists := payload["rootPath"]; exists {
		t.Fatalf("rootPath leaked: %#v", payload)
	}
	if payload["rootHandle"] == "" {
		t.Fatalf("missing opaque handle: %#v", payload)
	}

	stored, err := config.FindWorkspaceBridge("default", "bridge-1")
	if err != nil {
		t.Fatal(err)
	}
	resolved, _ := filepath.EvalSymlinks(root)
	if stored.LocalPath != resolved || stored.BridgeToken != "brg_secret" {
		t.Fatalf("local store missing path/token: %+v", stored)
	}
	path, _ := config.WorkspaceBridgeStorePath("default")
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatalf("store mode: %v %v", info, err)
	}
}
