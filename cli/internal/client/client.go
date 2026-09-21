// Package client provides the HTTP and SSE clients used by SpaceAgent CLI.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// RefreshFunc rotates an expired access token. The caller owns persistence of
// both access and refresh tokens; the client only caches the returned access
// token for the current process.
type RefreshFunc func(context.Context) (string, error)

type Client struct {
	BaseURL string
	Token   string

	httpClient *http.Client
	refreshFn  RefreshFunc
	tokenMu    sync.RWMutex
	refreshMu  sync.Mutex
}

// APIError is the stable error shape returned by the SpaceAgent gateway.
type APIError struct {
	StatusCode int
	Code       string
	Msg        string
	Details    []string
}

func (e *APIError) Error() string {
	if len(e.Details) > 0 {
		return fmt.Sprintf("[%d] %s: %s (%v)", e.StatusCode, e.Code, e.Msg, e.Details)
	}
	return fmt.Sprintf("[%d] %s: %s", e.StatusCode, e.Code, e.Msg)
}

func New(baseURL, token string) *Client {
	return NewWithRefresh(baseURL, token, nil)
}

// NewWithRefresh creates a client that retries a request once after a 401.
func NewWithRefresh(baseURL, token string, refreshFn RefreshFunc) *Client {
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Token:   token,
		httpClient: &http.Client{
			Timeout: 120 * time.Second,
		},
		refreshFn: refreshFn,
	}
}

func (c *Client) Get(path string, result interface{}) error {
	return c.doRequest(http.MethodGet, path, nil, nil, result)
}

func (c *Client) GetWithParams(path string, params url.Values, result interface{}) error {
	return c.doRequest(http.MethodGet, path, params, nil, result)
}

func (c *Client) Post(path string, body interface{}, result interface{}) error {
	return c.doRequestWithContext(context.Background(), http.MethodPost, path, nil, body, result)
}

// PostWithHeaders sends an authenticated JSON request with bounded caller-supplied
// protocol headers. It is used for proof-of-possession tokens such as Workspace Bridge.
func (c *Client) PostWithHeaders(
	path string,
	body interface{},
	headers map[string]string,
	result interface{},
) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("序列化请求体失败: %w", err)
	}
	if body == nil {
		payload = nil
	}
	return c.doBufferedRequest(
		context.Background(), http.MethodPost, path, nil, payload,
		"application/json", headers, result, true,
	)
}

func (c *Client) Put(path string, body interface{}, result interface{}) error {
	return c.doRequestWithContext(context.Background(), http.MethodPut, path, nil, body, result)
}

func (c *Client) Patch(path string, body interface{}, result interface{}) error {
	return c.doRequestWithContext(context.Background(), http.MethodPatch, path, nil, body, result)
}

func (c *Client) Delete(path string, result interface{}) error {
	return c.doRequest(http.MethodDelete, path, nil, nil, result)
}

func (c *Client) PostWithContext(ctx context.Context, path string, body interface{}, result interface{}) error {
	return c.doRequestWithContext(ctx, http.MethodPost, path, nil, body, result)
}

// PostRaw sends a replayable custom-content request such as multipart/form-data.
func (c *Client) PostRaw(path string, body io.Reader, contentType string, result interface{}) error {
	payload, err := io.ReadAll(body)
	if err != nil {
		return fmt.Errorf("读取请求体失败: %w", err)
	}
	return c.doBufferedRequest(
		context.Background(),
		http.MethodPost,
		path,
		nil,
		payload,
		contentType,
		nil,
		result,
		true,
	)
}

func (c *Client) doRequest(method, path string, params url.Values, body interface{}, result interface{}) error {
	return c.doRequestWithContext(context.Background(), method, path, params, body, result)
}

func (c *Client) doRequestWithContext(
	ctx context.Context,
	method string,
	path string,
	params url.Values,
	body interface{},
	result interface{},
) error {
	var payload []byte
	var err error
	if body != nil {
		payload, err = json.Marshal(body)
		if err != nil {
			return fmt.Errorf("序列化请求体失败: %w", err)
		}
	}
	contentType := ""
	if body != nil {
		contentType = "application/json"
	}
	return c.doBufferedRequest(ctx, method, path, params, payload, contentType, nil, result, true)
}

func (c *Client) doBufferedRequest(
	ctx context.Context,
	method string,
	path string,
	params url.Values,
	payload []byte,
	contentType string,
	headers map[string]string,
	result interface{},
	allowRefresh bool,
) error {
	fullURL := c.BaseURL + path
	if params != nil && len(params) > 0 {
		fullURL += "?" + params.Encode()
	}

	for attempt := 0; attempt < 2; attempt++ {
		token := c.accessToken()
		req, err := http.NewRequestWithContext(ctx, method, fullURL, bytes.NewReader(payload))
		if err != nil {
			return fmt.Errorf("创建请求失败: %w", err)
		}
		if contentType != "" {
			req.Header.Set("Content-Type", contentType)
		}
		for name, value := range headers {
			if strings.TrimSpace(name) != "" {
				req.Header.Set(name, value)
			}
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}

		resp, err := c.httpClient.Do(req)
		if err != nil {
			return fmt.Errorf("请求失败（服务器可能未启动）: %w", err)
		}
		respBody, readErr := io.ReadAll(resp.Body)
		closeErr := resp.Body.Close()
		if readErr != nil {
			return fmt.Errorf("读取响应失败: %w", readErr)
		}
		if closeErr != nil {
			return fmt.Errorf("关闭响应失败: %w", closeErr)
		}

		if resp.StatusCode == http.StatusUnauthorized &&
			attempt == 0 &&
			allowRefresh &&
			token != "" &&
			shouldAutoRefresh(path) {
			if err := c.refreshAccessToken(ctx, token); err == nil {
				continue
			}
		}

		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return decodeAPIError(resp.StatusCode, respBody)
		}
		if result != nil {
			if err := json.Unmarshal(respBody, result); err != nil {
				return fmt.Errorf("解析响应失败: %w", err)
			}
		}
		return nil
	}
	return fmt.Errorf("request retry exhausted")
}

func decodeAPIError(statusCode int, body []byte) error {
	var apiErr ApiError
	if json.Unmarshal(body, &apiErr) == nil && apiErr.Message != "" {
		return &APIError{
			StatusCode: statusCode,
			Code:       apiErr.Code,
			Msg:        apiErr.Message,
			Details:    apiErr.Details,
		}
	}
	return &APIError{
		StatusCode: statusCode,
		Code:       "UNKNOWN",
		Msg:        strings.TrimSpace(string(body)),
	}
}

func shouldAutoRefresh(path string) bool {
	return !strings.HasPrefix(path, "/api/v1/auth/") && !strings.HasPrefix(path, "/auth/")
}

func (c *Client) accessToken() string {
	c.tokenMu.RLock()
	defer c.tokenMu.RUnlock()
	return c.Token
}

// refreshAccessToken serializes refresh-token rotation. Waiting requests reuse
// a token already refreshed by the first goroutine instead of rotating again.
func (c *Client) refreshAccessToken(ctx context.Context, rejectedToken string) error {
	if c.refreshFn == nil {
		return fmt.Errorf("automatic token refresh is not configured")
	}
	c.refreshMu.Lock()
	defer c.refreshMu.Unlock()

	if current := c.accessToken(); current != "" && current != rejectedToken {
		return nil
	}
	token, err := c.refreshFn(ctx)
	if err != nil {
		return err
	}
	if strings.TrimSpace(token) == "" {
		return fmt.Errorf("token refresh returned an empty access token")
	}
	c.tokenMu.Lock()
	c.Token = token
	c.tokenMu.Unlock()
	return nil
}
