export const DEFAULT_MODEL_CONTEXT_TOKENS = 200_000

export function modelContextTokens(value: FormDataEntryValue | null): number {
  const raw = String(value ?? '').trim()
  return raw ? Number(raw) : DEFAULT_MODEL_CONTEXT_TOKENS
}
