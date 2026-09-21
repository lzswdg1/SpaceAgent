# Credential contract

## Accepted local inputs

- Qwen/Bailian CSV: rows labelled `apiKey`, `openAiCompatible`, `dashScope`, and metadata.
- DeepSeek file: one key-shaped line; optional for the authoritative run.
- GitHub: user identity is used only in a browser opened from host-owned MCP OAuth. A password
  is never accepted by scripts and is not sufficient without a dedicated GitHub/OAuth App
  client ID/secret and exact callback allowlist. M31 owns the host OAuth/tool/checkout mapping.

The materializer writes only:

```text
QWEN_API_KEY
QWEN_OPENAI_BASE_URL
QWEN_DASHSCOPE_URL
DEEPSEEK_API_KEY (optional)
```

The output must live under `.run/real-e2e/<run-id>/secrets.env`, mode `0600`, and be removed
by the execution trap. Do not copy it into `.env.release` or an Artifact.

## Application mapping

- `QWEN_API_KEY` is passed to the Provider creation HTTP body and Java encrypts it.
- The same Qwen key may be injected as `PLATFORM_KNOWLEDGE_EMBEDDING_API_KEY` for the isolated
  JVM. It is never returned by the API.
- `QWEN_OPENAI_BASE_URL` is used for Provider and Embedding OpenAI-compatible calls.
- Default bounded models are `qwen-plus` and `text-embedding-v4`; override only when the
  supplied workspace does not have those entitlements.
- TypeScript Multi-Agent uses the Java ModelPool and needs no separate key.

## Required non-model release secrets

Generate random test-only DB/JWT/internal/model-encryption/MCP-encryption
values per isolated run. They are not model API keys and must not reuse production secrets.
