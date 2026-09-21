package client

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const (
	streamRequestTimeout = 3 * time.Minute
	maxSSEEventSize      = 2 * 1024 * 1024
)

// SSEEvent 表示一个 Server-Sent Events 事件。
type SSEEvent struct {
	Event string // 事件类型：thinking, delta, done, error
	Data  string // 事件数据（JSON 字符串）
}

// Content 从常见 SSE payload 中提取 content 字段。
func (e SSEEvent) Content() string {
	var payload struct {
		Content string `json:"content"`
	}
	if err := json.Unmarshal([]byte(e.Data), &payload); err != nil {
		return ""
	}
	return payload.Content
}

// StreamCallback 流式回调接口。
type StreamCallback func(event SSEEvent) error

// PostStream 发送 POST 请求并以 SSE 流式读取响应。
// 每收到一个 SSE 事件就调用 callback。
func (c *Client) PostStream(path string, body interface{}, callback StreamCallback) error {
	return c.PostStreamWithContext(context.Background(), path, body, callback)
}

// PostStreamWithContext 发送可取消的 POST 请求并以 SSE 流式读取响应。
func (c *Client) PostStreamWithContext(ctx context.Context, path string, body interface{}, callback StreamCallback) error {
	streamCtx, cancel := context.WithTimeout(ctx, streamRequestTimeout)
	defer cancel()

	fullURL := c.BaseURL + path

	var payload []byte
	if body != nil {
		var err error
		payload, err = json.Marshal(body)
		if err != nil {
			return fmt.Errorf("序列化请求体失败: %w", err)
		}
	}

	streamClient := *c.httpClient
	streamClient.Timeout = streamRequestTimeout

	for attempt := 0; attempt < 2; attempt++ {
		token := c.accessToken()
		req, err := http.NewRequestWithContext(streamCtx, http.MethodPost, fullURL, bytes.NewReader(payload))
		if err != nil {
			return fmt.Errorf("创建请求失败: %w", err)
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "text/event-stream")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}

		resp, err := streamClient.Do(req)
		if err != nil {
			return fmt.Errorf("请求失败（服务器可能未启动）: %w", err)
		}

		if resp.StatusCode == http.StatusUnauthorized &&
			attempt == 0 &&
			token != "" &&
			shouldAutoRefresh(path) {
			_, _ = java_compat_readAll(resp)
			_ = resp.Body.Close()
			if err := c.refreshAccessToken(streamCtx, token); err == nil {
				continue
			}
		}

		if resp.StatusCode != http.StatusOK {
			respBody, _ := java_compat_readAll(resp)
			_ = resp.Body.Close()
			return decodeAPIError(resp.StatusCode, respBody)
		}

		err = consumeSSE(streamCtx, resp.Body, callback)
		closeErr := resp.Body.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return fmt.Errorf("关闭 SSE 响应失败: %w", closeErr)
		}
		return nil
	}
	return fmt.Errorf("SSE request retry exhausted")
}

func consumeSSE(streamCtx context.Context, body io.Reader, callback StreamCallback) error {
	scanner := bufio.NewScanner(body)
	scanner.Buffer(make([]byte, 64*1024), maxSSEEventSize)
	var currentEvent SSEEvent

	for scanner.Scan() {
		line := scanner.Text()

		if line == "" {
			// 空行表示事件结束
			if currentEvent.Data != "" {
				if err := callback(currentEvent); err != nil {
					return err
				}
			}
			currentEvent = SSEEvent{}
			continue
		}

		if strings.HasPrefix(line, "event:") {
			currentEvent.Event = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		} else if strings.HasPrefix(line, "data:") {
			data := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
			if currentEvent.Data != "" {
				currentEvent.Data += "\n"
			}
			currentEvent.Data += data
		}
	}

	// 处理最后一个事件（如果没有尾部空行）
	if currentEvent.Data != "" {
		if err := callback(currentEvent); err != nil {
			return err
		}
	}

	if err := scanner.Err(); err != nil {
		if streamCtx.Err() != nil {
			return streamCtx.Err()
		}
		return fmt.Errorf("读取 SSE 流失败: %w", err)
	}
	return nil
}

func java_compat_readAll(resp *http.Response) ([]byte, error) {
	var buf bytes.Buffer
	_, err := buf.ReadFrom(resp.Body)
	return buf.Bytes(), err
}
