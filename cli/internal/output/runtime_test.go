package output

import (
	"bytes"
	"encoding/json"
	"errors"
	"testing"
)

func TestRuntimeEmitsSingleSuccessEnvelope(t *testing.T) {
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	runtime := NewRuntime(&stdout, &stderr)
	out, _, err := runtime.Begin("json", "spaceagent health", "work")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = out.Write([]byte("server is UP\n"))
	if rendered := runtime.Finish("json", "", "", nil); rendered {
		t.Fatal("success should not report an error as rendered")
	}

	var envelope map[string]any
	if err := json.Unmarshal(stdout.Bytes(), &envelope); err != nil {
		t.Fatalf("invalid JSON output: %v\n%s", err, stdout.String())
	}
	if envelope["ok"] != true || stderr.Len() != 0 {
		t.Fatalf("unexpected success envelope: %s; stderr=%s", stdout.String(), stderr.String())
	}
}

func TestRuntimeEmitsTypedConfirmationError(t *testing.T) {
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	runtime := NewRuntime(&stdout, &stderr)
	_, _, err := runtime.Begin("json", "spaceagent project source import-github", "default")
	if err != nil {
		t.Fatal(err)
	}
	commandErr := errors.New("确认后请添加 --yes")
	if rendered := runtime.Finish("json", "", "", commandErr); !rendered {
		t.Fatal("expected structured error to be rendered")
	}

	var envelope struct {
		OK    bool `json:"ok"`
		Error struct {
			Type    string `json:"type"`
			Subtype string `json:"subtype"`
		} `json:"error"`
	}
	if err := json.Unmarshal(stderr.Bytes(), &envelope); err != nil {
		t.Fatalf("invalid error JSON: %v\n%s", err, stderr.String())
	}
	if envelope.OK || envelope.Error.Type != "confirmation" || ExitCode(commandErr) != 10 {
		t.Fatalf("unexpected error envelope: %s", stderr.String())
	}
}
