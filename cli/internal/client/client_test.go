package client

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestClientDeleteUsesDeleteMethod(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete {
			t.Fatalf("expected %s request, got %s", http.MethodDelete, r.Method)
		}
		if r.URL.Path != "/agents/123" {
			t.Fatalf("expected path /agents/123, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data": map[string]string{
				"status": "deleted",
			},
			"message": "OK",
		})
	}))
	defer server.Close()

	c := New(server.URL, "")
	var resp ApiResponse[map[string]string]
	if err := c.Delete("/agents/123", &resp); err != nil {
		t.Fatalf("Delete returned error: %v", err)
	}
	if got := resp.Data["status"]; got != "deleted" {
		t.Fatalf("expected deleted status, got %q", got)
	}
}

func TestClientRefreshesExpiredTokenAndRetriesOnce(t *testing.T) {
	t.Parallel()

	var resourceCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		resourceCalls.Add(1)
		if r.Header.Get("Authorization") != "Bearer fresh-token" {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"code": "TOKEN_EXPIRED", "message": "expired",
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data":    map[string]string{"status": "ok"},
		})
	}))
	defer server.Close()

	var refreshCalls atomic.Int32
	c := NewWithRefresh(server.URL, "expired-token", func(context.Context) (string, error) {
		refreshCalls.Add(1)
		return "fresh-token", nil
	})
	var response ApiResponse[map[string]string]
	if err := c.Get("/resource", &response); err != nil {
		t.Fatal(err)
	}
	if refreshCalls.Load() != 1 || resourceCalls.Load() != 2 {
		t.Fatalf("expected one refresh and two requests, got refresh=%d requests=%d",
			refreshCalls.Load(), resourceCalls.Load())
	}
}

func TestConcurrentRequestsShareSingleTokenRefresh(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer fresh-token" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"code":"TOKEN_EXPIRED","message":"expired"}`))
			return
		}
		_, _ = w.Write([]byte(`{"success":true,"data":{"status":"ok"}}`))
	}))
	defer server.Close()

	var refreshCalls atomic.Int32
	c := NewWithRefresh(server.URL, "expired-token", func(context.Context) (string, error) {
		refreshCalls.Add(1)
		time.Sleep(20 * time.Millisecond)
		return "fresh-token", nil
	})

	var wg sync.WaitGroup
	errs := make(chan error, 8)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var response ApiResponse[map[string]string]
			errs <- c.Get("/resource", &response)
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	if refreshCalls.Load() != 1 {
		t.Fatalf("expected one refresh rotation, got %d", refreshCalls.Load())
	}
}

func TestClientPutUsesPutMethod(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut {
			t.Fatalf("expected %s request, got %s", http.MethodPut, r.Method)
		}
		if r.URL.Path != "/profile" {
			t.Fatalf("expected path /profile, got %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data": map[string]string{
				"status": "updated",
			},
			"message": "OK",
		})
	}))
	defer server.Close()

	c := New(server.URL, "")
	var resp ApiResponse[map[string]string]
	if err := c.Put("/profile", map[string]string{"timezone": "Asia/Shanghai"}, &resp); err != nil {
		t.Fatalf("Put returned error: %v", err)
	}
	if got := resp.Data["status"]; got != "updated" {
		t.Fatalf("expected updated status, got %q", got)
	}
}

func TestClientPatchUsesPatchMethod(t *testing.T) {
	t.Parallel()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPatch || r.URL.Path != "/members/user-2" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data":    map[string]string{"role": "VIEWER"},
		})
	}))
	defer server.Close()

	var response ApiResponse[map[string]string]
	if err := New(server.URL, "").Patch(
		"/members/user-2",
		map[string]string{"role": "VIEWER"},
		&response,
	); err != nil {
		t.Fatal(err)
	}
	if response.Data["role"] != "VIEWER" {
		t.Fatalf("unexpected response: %#v", response.Data)
	}
}
