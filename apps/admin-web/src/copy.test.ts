import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { text } from './copy'
import { extraMessages } from './copyExtra'
import { displayCount } from './adminViewModel'
import { errorText } from './errorText'

const root = fileURLToPath(new URL('.', import.meta.url))
const files = ['App.tsx', 'AuthGate.tsx', ...readdirSync(root + 'pages').filter(f => f.endsWith('.tsx')).map(f => 'pages/' + f)]

describe('Administrator translations and truthful presentation', () => {
  it('keeps list totals in pagination and removes duplicate decorative identity text', () => {
    for (const page of ['UsersPage', 'OrganizationsPage', 'CommandsPage', 'CleanupPage', 'McpRegistryPage']) {
      const source = readFileSync(root + 'pages/' + page + '.tsx', 'utf8')
      expect(source).not.toContain('className="freshness"')
      expect(source).toContain('className="pagination')
      expect(source).not.toContain('HIGH PRIVILEGE')
    }
    const auth = readFileSync(root + 'AuthGate.tsx', 'utf8')
    expect(auth).not.toContain('auth-visual')
    expect(auth.match(/tx\('loginTitle'\)/g)).toHaveLength(1)
    expect(auth).not.toContain('totpTitle')
    expect(auth).toContain('tx(\'securityBoundaryCopy\')')
    const admin = readFileSync(root + 'pages/AdministratorsPage.tsx', 'utf8')
    expect(admin).not.toContain('administrator.remainingRecoveryCodes')
    expect(admin).not.toContain('api.reauthenticate')
    expect(admin).toContain('configuredAccountHint')
    expect(readFileSync(root + 'pages/OrganizationsPage.tsx', 'utf8')).toContain('storageCleanupWarning')
  })
  it('identifies the administrator once by login without redundant profile labels', () => {
    const source = readFileSync(root + 'pages/AdministratorsPage.tsx', 'utf8')
    expect(source.match(/administrator\.loginName/g)).toHaveLength(1)
    expect(source).not.toContain('administrator.displayName')
    expect(source).not.toContain('currentAdministrator')
    expect(source).not.toContain('singleton-profile')
    expect(source).not.toContain('singleton-boundary')
  })
  it('keeps missing counters distinct from measured zero', () => {
    expect(displayCount(undefined)).toBe('—')
    expect(displayCount(null)).toBe('—')
    expect(displayCount(NaN)).toBe('—')
    expect(displayCount(0)).toBe('0')
    expect(displayCount(4)).toBe('4')
  })
  it('provides Chinese, Japanese and English for every added label', () => {
    for (const values of Object.values(extraMessages)) {
      expect(values).toHaveLength(3)
      for (const value of values) expect(value.trim()).not.toBe('')
    }
    expect(text('zh', 'UNKNOWN')).toBe('结果不确定')
    expect(text('ja', 'UNKNOWN')).toBe('結果不明')
    expect(text('ja', 'unknownModelCalls')).toBe('結果不明のモデル呼び出し')
  })
  it('does not bypass translations for static JSX labels or change enum option values', () => {
    const missed: string[] = []
    for (const file of files) {
      const source = readFileSync(root + file, 'utf8')
      const tree = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
      const walk = (node: ts.Node) => {
        if (ts.isJsxText(node) && /[A-Za-z]/.test(node.text) && !['SpaceAgent', 'V'].includes(node.text.trim())) missed.push(file + ': ' + node.text.trim())
        if (ts.isCallExpression(node) && node.expression.getText(tree) === 'tx' && node.arguments[0] && ts.isStringLiteral(node.arguments[0])) {
          const key = node.arguments[0].text
          if (!['MCP'].includes(key) && text('zh', key) === key) missed.push(file + ': missing key ' + key)
        }
        if (ts.isJsxOpeningElement(node) && node.tagName.getText(tree) === 'option') {
          expect(node.attributes.properties.some(p => ts.isJsxAttribute(p) && p.name.getText(tree) === 'value'), file + ' enum option must retain its wire value').toBe(true)
        }
        ts.forEachChild(node, walk)
      }
      walk(tree)
    }
    expect(missed).toEqual([])
  })
  it('localizes failures without rendering arbitrary backend response text', () => {
    const tx = (key: string) => text('zh', key)
    expect(errorText('Platform administration data is unavailable', tx)).toBe(tx('dataUnavailable'))
    expect(errorText('Invalid MFA', tx)).toBe(tx('mfaError'))
    expect(errorText('private payload', tx)).toBe(tx('requestFailed'))
  })
  it('removes obsolete governance calls and separates organization failure from empty results', () => {
    expect(readFileSync(root + 'adminApi.ts', 'utf8')).not.toContain('agent-version-governance')
    const overview = readFileSync(root + 'pages/OverviewPage.tsx', 'utf8')
    expect(overview).not.toContain('VERIFIED')
    expect(overview).not.toContain('LIVE')
    expect(overview).toContain('p.inference.unknownModelCalls')
    expect(readFileSync(root + 'pages/OrganizationsPage.tsx', 'utf8')).toContain('!loading && !error && !items.length')
  })
})
