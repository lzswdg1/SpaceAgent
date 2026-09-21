package tenant

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
	"github.com/lzswdg1/SpaceAgent/cli/internal/config"
	"github.com/lzswdg1/SpaceAgent/cli/internal/factory"
)

func TestSwitchTenantPersistsTenantScopedTokens(t *testing.T) {
	t.Setenv("SPACEAGENT_CONFIG_DIR", t.TempDir())
	tenantID := "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/tenants":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"success": true,
				"data": []map[string]any{{
					"id": tenantID, "slug": "agent-team", "name": "Agent Team",
					"type": "ORGANIZATION", "currentUserRole": "OWNER",
				}},
			})
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/tenants/"+tenantID+"/switch":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"success": true,
				"data": map[string]any{
					"token": "tenant-token", "refreshToken": "tenant-refresh",
					"userId": "user-1", "username": "alice", "role": "USER",
					"tenantId": tenantID, "tenantRole": "OWNER",
				},
			})
		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
	}))
	defer server.Close()

	cfg := &config.Config{
		Server: server.URL, Token: "personal-token", RefreshToken: "personal-refresh",
		UserID: "user-1", Username: "alice", Role: "USER", TenantID: "user-1", TenantRole: "OWNER",
	}
	if err := config.Save(cfg); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	f := tenantTestFactory(cfg, server.URL, &out)

	if err := newSwitchCmd(f).RunE(nil, []string{"agent-team"}); err != nil {
		t.Fatal(err)
	}
	persisted, err := config.Load()
	if err != nil {
		t.Fatal(err)
	}
	if persisted.Token != "tenant-token" || persisted.RefreshToken != "tenant-refresh" {
		t.Fatalf("tenant tokens were not persisted: %#v", persisted)
	}
	if persisted.TenantID != tenantID || persisted.TenantRole != "OWNER" {
		t.Fatalf("tenant context was not persisted: %#v", persisted)
	}
}

func TestMemberRoleUpdateUsesPatchContract(t *testing.T) {
	t.Parallel()
	tenantID := "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPatch || r.URL.Path != "/api/v1/tenants/"+tenantID+"/members/user-2" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		var request client.UpdateTenantMemberRoleRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatal(err)
		}
		if request.Role != "VIEWER" {
			t.Fatalf("unexpected role: %s", request.Role)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data": map[string]any{
				"userId": "user-2", "username": "bob", "role": "VIEWER", "status": "ACTIVE",
			},
		})
	}))
	defer server.Close()

	cfg := &config.Config{Server: server.URL, Token: "token", TenantID: tenantID, TenantRole: "OWNER"}
	var out bytes.Buffer
	f := tenantTestFactory(cfg, server.URL, &out)
	cmd := newSetRoleCmd(f)
	if err := cmd.Flags().Set("role", "viewer"); err != nil {
		t.Fatal(err)
	}
	if err := cmd.RunE(cmd, []string{"user-2"}); err != nil {
		t.Fatal(err)
	}
}

func tenantTestFactory(cfg *config.Config, serverURL string, out *bytes.Buffer) *factory.Factory {
	c := client.New(serverURL, cfg.Token)
	return &factory.Factory{
		ConfigFunc: func() (*config.Config, error) { return cfg, nil },
		ClientFunc: func() (*client.Client, error) { return c, nil },
		IOStreams:  &factory.IOStreams{In: bytes.NewReader(nil), Out: out, ErrOut: io.Discard},
	}
}
