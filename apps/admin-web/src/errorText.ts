export function errorText(message: string, tx: (key: string) => string) {
  if (/MFA|TOTP|password|credential|密码|验证码/i.test(message)) return tx('mfaError')
  if (/\b(unavailable|load|loading|fetch|network|404|503)\b/i.test(message)) return tx('dataUnavailable')
  return tx('requestFailed')
}
