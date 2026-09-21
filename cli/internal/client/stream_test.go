package client

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPostStreamWithContextSupportsLargeEvents(t *testing.T) {
	content := strings.Repeat("a", 70*1024)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "event: delta\ndata: {\"content\":%q}\n\n", content)
	}))
	defer server.Close()

	c := New(server.URL, "token")
	var received string
	err := c.PostStreamWithContext(context.Background(), "/stream", map[string]string{"message": "hello"}, func(event SSEEvent) error {
		received = event.Content()
		return nil
	})
	if err != nil {
		t.Fatalf("PostStreamWithContext() error = %v", err)
	}
	if received != content {
		t.Fatalf("received content length = %d, want %d", len(received), len(content))
	}
}

func TestPostStreamWithContextCanBeCancelled(t *testing.T) {
	started := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		close(started)
		<-r.Context().Done()
	}))
	defer server.Close()

	ctx, cancel := context.WithCancel(context.Background())
	c := New(server.URL, "token")
	result := make(chan error, 1)
	go func() {
		result <- c.PostStreamWithContext(ctx, "/stream", nil, func(SSEEvent) error { return nil })
	}()

	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("stream request did not start")
	}
	cancel()

	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error = %v, want context.Canceled", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stream request was not cancelled")
	}
}

func TestPostStreamRefreshesBeforeConsumingEvents(t *testing.T) {
	var requests int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.Header.Get("Authorization") != "Bearer fresh-token" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"code":"TOKEN_EXPIRED","message":"expired"}`))
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = fmt.Fprint(w, "event: delta\ndata: {\"content\":\"hello\"}\n\n")
	}))
	defer server.Close()

	refreshes := 0
	c := NewWithRefresh(server.URL, "expired-token", func(context.Context) (string, error) {
		refreshes++
		return "fresh-token", nil
	})
	var received string
	err := c.PostStreamWithContext(context.Background(), "/stream", nil, func(event SSEEvent) error {
		received = event.Content()
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if refreshes != 1 || requests != 2 || received != "hello" {
		t.Fatalf("unexpected refresh result: refreshes=%d requests=%d received=%q", refreshes, requests, received)
	}
}
