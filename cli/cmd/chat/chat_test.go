package chat

import (
	"bytes"
	"strings"
	"testing"
)

func TestTruncateConvID(t *testing.T) {
	tests := []struct {
		name string
		id   string
		want string
	}{
		{
			name: "empty conversation is new conversation",
			id:   "",
			want: "新对话",
		},
		{
			name: "blank conversation is new conversation",
			id:   "   ",
			want: "新对话",
		},
		{
			name: "short id is unchanged",
			id:   "abc123",
			want: "abc123",
		},
		{
			name: "long id is truncated for display",
			id:   "1234567890abcdef",
			want: "12345678...",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := truncateConvID(tt.id); got != tt.want {
				t.Fatalf("truncateConvID(%q) = %q, want %q", tt.id, got, tt.want)
			}
		})
	}
}

func TestHandleThinkingToggle(t *testing.T) {
	var out bytes.Buffer
	showThinking := false

	if !handleThinkingToggle(&out, "/thinking", &showThinking) {
		t.Fatal("expected /thinking to be handled")
	}
	if !showThinking {
		t.Fatal("expected showThinking to be enabled")
	}
	if !strings.Contains(out.String(), "思考过程显示已开启") {
		t.Fatalf("expected enabled message, got %q", out.String())
	}

	out.Reset()
	if !handleThinkingToggle(&out, "/think", &showThinking) {
		t.Fatal("expected /think to be handled")
	}
	if showThinking {
		t.Fatal("expected showThinking to be disabled")
	}
	if !strings.Contains(out.String(), "思考过程显示已关闭") {
		t.Fatalf("expected disabled message, got %q", out.String())
	}
}

func TestHandleThinkingToggleIgnoresOtherCommands(t *testing.T) {
	var out bytes.Buffer
	showThinking := false

	if handleThinkingToggle(&out, "/model", &showThinking) {
		t.Fatal("expected /model to be ignored")
	}
	if showThinking {
		t.Fatal("expected showThinking to stay disabled")
	}
	if out.Len() != 0 {
		t.Fatalf("expected no output, got %q", out.String())
	}
}
