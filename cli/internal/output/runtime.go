package output

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync/atomic"

	"github.com/lzswdg1/SpaceAgent/cli/internal/client"
)

const (
	FormatPretty = "pretty"
	FormatJSON   = "json"
)

var structuredMode atomic.Bool

type Runtime struct {
	rawOut  io.Writer
	rawErr  io.Writer
	out     bytes.Buffer
	errOut  bytes.Buffer
	format  string
	command string
	profile string
	started bool
}

type envelopeMeta struct {
	Command string `json:"command,omitempty"`
	Profile string `json:"profile,omitempty"`
}

type successEnvelope struct {
	OK   bool         `json:"ok"`
	Data any          `json:"data"`
	Meta envelopeMeta `json:"meta,omitempty"`
}

type errorEnvelope struct {
	OK    bool         `json:"ok"`
	Error problem      `json:"error"`
	Meta  envelopeMeta `json:"meta,omitempty"`
}

type problem struct {
	Type       string   `json:"type"`
	Subtype    string   `json:"subtype"`
	Message    string   `json:"message"`
	Hint       string   `json:"hint,omitempty"`
	HTTPStatus int      `json:"httpStatus,omitempty"`
	Retryable  bool     `json:"retryable,omitempty"`
	Details    []string `json:"details,omitempty"`
}

func NewRuntime(out, errOut io.Writer) *Runtime {
	return &Runtime{rawOut: out, rawErr: errOut, format: FormatPretty}
}

func NormalizeFormat(format string) (string, error) {
	format = strings.ToLower(strings.TrimSpace(format))
	if format == "" {
		return FormatPretty, nil
	}
	switch format {
	case FormatPretty, FormatJSON:
		return format, nil
	default:
		return "", fmt.Errorf("unsupported output format %q; use pretty or json", format)
	}
}

// Begin configures writers before command guards and RunE execute.
func (r *Runtime) Begin(format, command, profile string) (io.Writer, io.Writer, error) {
	normalized, err := NormalizeFormat(format)
	if err != nil {
		return r.rawOut, r.rawErr, err
	}
	r.format = normalized
	r.command = command
	r.profile = profile
	r.started = true
	if normalized == FormatJSON {
		structuredMode.Store(true)
		return &r.out, &r.errOut, nil
	}
	structuredMode.Store(false)
	return r.rawOut, r.rawErr, nil
}

// Finish emits exactly one JSON object for structured invocations.
// It returns true when the error has already been rendered.
func (r *Runtime) Finish(format, command, profile string, commandErr error) bool {
	if !r.started {
		normalized, err := NormalizeFormat(format)
		if err != nil {
			normalized = FormatPretty
		}
		r.format = normalized
		r.command = command
		r.profile = profile
	}
	if r.format != FormatJSON {
		structuredMode.Store(false)
		return false
	}
	defer structuredMode.Store(false)

	meta := envelopeMeta{Command: r.command, Profile: r.profile}
	if commandErr == nil {
		text := cleanTerminalText(r.out.String())
		data := map[string]any{}
		if text != "" {
			data["output"] = text
		}
		_ = writeJSON(r.rawOut, successEnvelope{OK: true, Data: data, Meta: meta})
		return false
	}

	p := classifyError(commandErr)
	captured := cleanTerminalText(strings.TrimSpace(r.errOut.String() + "\n" + r.out.String()))
	if captured != "" && captured != p.Message {
		p.Details = []string{captured}
	}
	_ = writeJSON(r.rawErr, errorEnvelope{OK: false, Error: p, Meta: meta})
	return true
}

func IsStructured() bool {
	return structuredMode.Load()
}

func ExitCode(err error) int {
	if err == nil {
		return 0
	}
	p := classifyError(err)
	switch p.Type {
	case "validation":
		return 2
	case "authentication", "authorization", "config":
		return 3
	case "network":
		return 4
	case "internal":
		return 5
	case "policy":
		return 6
	case "confirmation":
		return 10
	default:
		return 1
	}
}

func classifyError(err error) problem {
	if errors.Is(err, context.Canceled) {
		return problem{Type: "network", Subtype: "cancelled", Message: "request cancelled"}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return problem{
			Type:      "network",
			Subtype:   "timeout",
			Message:   err.Error(),
			Retryable: true,
		}
	}

	var apiErr *client.APIError
	if errors.As(err, &apiErr) {
		category := "api"
		switch apiErr.StatusCode {
		case http.StatusUnauthorized:
			category = "authentication"
		case http.StatusForbidden:
			category = "authorization"
		case http.StatusBadRequest, http.StatusNotFound, http.StatusConflict, http.StatusUnprocessableEntity:
			category = "validation"
		}
		return problem{
			Type:       category,
			Subtype:    stableSubtype(apiErr.Code, http.StatusText(apiErr.StatusCode)),
			Message:    apiErr.Msg,
			HTTPStatus: apiErr.StatusCode,
			Retryable:  apiErr.StatusCode == http.StatusTooManyRequests || apiErr.StatusCode >= 500,
			Details:    apiErr.Details,
		}
	}

	var netErr net.Error
	if errors.As(err, &netErr) {
		return problem{
			Type:      "network",
			Subtype:   "transport",
			Message:   err.Error(),
			Retryable: true,
		}
	}

	message := strings.TrimSpace(err.Error())
	lower := strings.ToLower(message)
	switch {
	case strings.Contains(message, "--yes") || strings.Contains(lower, "confirmation"):
		return problem{
			Type:    "confirmation",
			Subtype: "confirmation_required",
			Message: message,
			Hint:    "review the action and rerun with --yes",
		}
	case strings.Contains(message, "未登录"),
		strings.Contains(lower, "token"),
		strings.Contains(lower, "login"):
		return problem{
			Type:    "authentication",
			Subtype: "token_missing",
			Message: message,
			Hint:    "run `spaceagent auth login`",
		}
	case strings.Contains(lower, "profile"),
		strings.Contains(lower, "config"):
		return problem{Type: "config", Subtype: "invalid_config", Message: message}
	case strings.Contains(lower, "required"),
		strings.Contains(lower, "invalid"),
		strings.Contains(message, "参数"),
		strings.Contains(message, "不能为空"):
		return problem{Type: "validation", Subtype: "invalid_argument", Message: message}
	default:
		return problem{Type: "internal", Subtype: "unexpected", Message: message}
	}
}

func stableSubtype(values ...string) string {
	value := ""
	for _, candidate := range values {
		if strings.TrimSpace(candidate) != "" {
			value = candidate
			break
		}
	}
	value = strings.ToLower(strings.TrimSpace(value))
	value = regexp.MustCompile(`[^a-z0-9]+`).ReplaceAllString(value, "_")
	value = strings.Trim(value, "_")
	if value == "" {
		return "unknown"
	}
	return value
}

var ansiPattern = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)

func cleanTerminalText(value string) string {
	value = ansiPattern.ReplaceAllString(value, "")
	return strings.TrimSpace(value)
}

func writeJSON(out io.Writer, value any) error {
	encoder := json.NewEncoder(out)
	encoder.SetEscapeHTML(false)
	return encoder.Encode(value)
}
