package output

import "strings"

func FriendlyAIError(message string) string {
	msg := strings.TrimSpace(strings.Join(strings.Fields(message), " "))
	lower := strings.ToLower(msg)

	switch {
	case strings.Contains(lower, "401"),
		strings.Contains(lower, "unauthorized"),
		strings.Contains(lower, "invalid api key"),
		strings.Contains(lower, "incorrect api key"):
		return "模型 API Key 无效或未配置，请检查后端 .env 里的 AI_API_KEY / AI_API_BASE_URL / AI_MODEL。"
	case strings.Contains(lower, "403"),
		strings.Contains(lower, "forbidden"),
		strings.Contains(lower, "permission"):
		return "模型服务拒绝访问，请确认 API Key 权限、模型开通状态和 Base URL。"
	case strings.Contains(lower, "404"),
		strings.Contains(lower, "model not found"),
		strings.Contains(lower, "not exist"):
		return "模型不存在或模型名配置错误，请检查 AI_MODEL。"
	case strings.Contains(lower, "429"),
		strings.Contains(lower, "rate limit"),
		strings.Contains(lower, "too many requests"),
		strings.Contains(lower, "quota"):
		return "模型服务限流或额度不足，请检查余额、配额或稍后重试。"
	case strings.Contains(lower, "timeout"),
		strings.Contains(lower, "timed out"),
		strings.Contains(lower, "connect"):
		return "模型服务连接超时，请检查网络、Base URL 或服务商状态。"
	case strings.Contains(lower, "embedding"),
		strings.Contains(lower, "dimension"):
		return "Embedding 配置异常，请确认 AI_EMBEDDING_MODEL 与 AI_EMBEDDING_DIMENSIONS 一致。"
	case msg != "":
		return msg
	default:
		return "模型调用失败，请检查后端日志。"
	}
}
