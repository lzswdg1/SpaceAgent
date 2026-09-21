import type { Language } from '../../copy'

const copy = {
  zh: { day: '最近 1 天', week: '最近 7 天', all: '全部', range: '统计时间范围',
    ranking: 'Agent 累计用量', scope: '全部历史 · 按 Token 消耗降序', agent: 'Agent', calls: '调用次数',
    input: '输入', output: '输出', cache: '缓存读 / 写', cost: '已知费用（USD）',
    incomplete: '费用不完整：存在未计价或结果未知的调用', empty: '暂无 Agent 调用记录',
    unavailable: '无法加载 Agent 累计用量', refresh: '刷新', loading: '正在加载统计…',
    truncated: '仅显示前 200 个 Agent，更多记录未展示。',
    scopeHint: '统计 Chat / Project 模型调用，不含独立知识库 Embedding；费用不是供应商最终账单。' },
  en: { day: 'Last 24 hours', week: 'Last 7 days', all: 'All time', range: 'Statistics period',
    ranking: 'Lifetime Agent usage', scope: 'All time · highest token usage first', agent: 'Agent', calls: 'Calls',
    input: 'Input', output: 'Output', cache: 'Cache read / write', cost: 'Known cost (USD)',
    incomplete: 'Incomplete cost: unpriced or unknown calls exist', empty: 'No Agent calls recorded',
    unavailable: 'Could not load lifetime Agent usage', refresh: 'Refresh', loading: 'Loading statistics…',
    truncated: 'Only the top 200 Agents are shown; more records exist.',
    scopeHint: 'Chat / Project model calls only; standalone Knowledge Embedding is excluded. Costs are not the final provider invoice.' },
  ja: { day: '過去 24 時間', week: '過去 7 日', all: '全期間', range: '集計期間',
    ranking: 'Agent 累計使用量', scope: '全期間 · Token 使用量の多い順', agent: 'Agent', calls: '呼出回数',
    input: '入力', output: '出力', cache: 'キャッシュ読取 / 書込', cost: '確認済み費用（USD）',
    incomplete: '費用未確定：未価格または結果不明の呼出しがあります', empty: 'Agent の呼出記録はありません',
    unavailable: 'Agent 累計使用量を読み込めません', refresh: '更新', loading: '統計を読み込み中…',
    truncated: '上位 200 Agent のみ表示しています。ほかにも記録があります。',
    scopeHint: 'Chat / Project のモデル呼出しのみ。独立した Knowledge Embedding は含まれず、費用はプロバイダーの最終請求額ではありません。' },
} as const

export const usageCopy = (language: Language) => copy[language]
export const formatUsageCost = (value: number | null | undefined) =>
  value == null ? '—' : value > 0 && value < 0.01 ? '<$0.01' : `$${value.toFixed(2)}`
