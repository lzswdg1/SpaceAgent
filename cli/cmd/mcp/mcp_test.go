package mcp

import (
	"reflect"
	"testing"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
)

func TestBuildPayloadFromFlagsSupportsStreamableHTTP(t *testing.T) {
	enabled := true
	payload, err := buildPayloadFromFlags(serverFlags{
		name:         "remote-search",
		transport:    "streamable-http",
		url:          "https://mcp.example.com/mcp",
		headers:      []string{"Authorization=Bearer test-token", "X-Tenant=demo"},
		allowedTools: []string{"search", "read_*"},
	}, nil, enabled)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}
	if payload.Transport != "streamable-http" || payload.URL != "https://mcp.example.com/mcp" {
		t.Fatalf("unexpected transport payload: %+v", payload)
	}
	if payload.Headers["Authorization"] != "Bearer test-token" || payload.Headers["X-Tenant"] != "demo" {
		t.Fatalf("unexpected headers: %#v", payload.Headers)
	}
	if !reflect.DeepEqual(payload.AllowedTools, []string{"search", "read_*"}) {
		t.Fatalf("unexpected allowed tools: %#v", payload.AllowedTools)
	}
}

func TestBuildPayloadFromFlagsKeepsExistingRemoteSecrets(t *testing.T) {
	enabled := true
	current := client.MCPServerConfigResponse{
		Name:         "remote-search",
		Transport:    "streamable-http",
		URL:          "https://mcp.example.com/mcp",
		Headers:      map[string]string{"Authorization": "********"},
		AllowedTools: []string{"search"},
	}
	payload, err := buildPayloadFromFlags(serverFlags{name: "renamed"}, &current, enabled)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}
	if payload.Name != "renamed" || payload.Headers["Authorization"] != "********" {
		t.Fatalf("existing remote config was not preserved: %+v", payload)
	}
	if !reflect.DeepEqual(payload.AllowedTools, []string{"search"}) {
		t.Fatalf("unexpected allowed tools: %#v", payload.AllowedTools)
	}
}

func TestBuildPayloadFromFlagsNormalizesHTTPAlias(t *testing.T) {
	payload, err := buildPayloadFromFlags(serverFlags{
		name:      "remote",
		transport: "http",
		url:       "http://127.0.0.1:3000/mcp",
	}, nil, true)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}
	if payload.Transport != "streamable-http" {
		t.Fatalf("expected streamable-http, got %q", payload.Transport)
	}
	if !reflect.DeepEqual(payload.AllowedTools, []string{"*"}) {
		t.Fatalf("expected default wildcard, got %#v", payload.AllowedTools)
	}
}
