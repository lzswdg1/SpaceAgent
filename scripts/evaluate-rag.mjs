// Offline evaluator only: no model, network, credentials or production access.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
export function evaluate(gold, predictions, k = 5) {
  if (!Number.isInteger(k) || k < 1 || k > 100) throw Error('k must be 1..100');
  const byId = new Map(gold.queries.map(q => [q.id, q]));
  const corpus = new Set(gold.documents.map(d => d.id));
  const seen = new Set();
  const modes = new Map();
  for (const row of predictions) {
    const q = byId.get(row.id), key = `${row.mode}:${row.id}`;
    if (!q || seen.has(key) || !['dense', 'hybrid', 'rerank'].includes(row.mode) || !Array.isArray(row.hits)) throw Error('Invalid/duplicate prediction');
    seen.add(key);
    const docs = [...new Set(row.hits.map(h => h.documentId))].slice(0, k);
    const relevant = new Set(q.relevantDocumentIds);
    if (!relevant.size) throw Error('Gold judgments required');
    const found = docs.filter(d => relevant.has(d)).length;
    const first = docs.findIndex(d => relevant.has(d));
    const dcg = docs.reduce((s,d,i) => s + (relevant.has(d) ? 1 / Math.log2(i + 2) : 0), 0);
    const ideal = Array.from({length: Math.min(k, relevant.size)}, (_,i) => 1 / Math.log2(i + 2)).reduce((a,b) => a+b, 0);
    const valid = row.hits.slice(0,k).filter(h => corpus.has(h.documentId) && typeof h.chunkId === 'string' && h.chunkId.length > 0 && /^[a-f0-9]{64}$/.test(h.contentHash ?? '')).length;
    const acc = modes.get(row.mode) ?? {queries: 0, recall: 0, mrr: 0, ndcg: 0, structurallyValidCitations: 0, citations: 0};
    acc.queries++; acc.recall += found/relevant.size; acc.mrr += first < 0 ? 0 : 1/(first+1); acc.ndcg += dcg/ideal;
    acc.structurallyValidCitations += valid; acc.citations += Math.min(k,row.hits.length); modes.set(row.mode,acc);
  }
  return {k, qualityAcceptance: 'EXTERNAL_ACCEPTANCE_PENDING', citationScope: 'structure and corpus membership only; live hash/ACL validity requires backend verification',
    modes: Object.fromEntries([...modes].map(([mode,a]) => [mode,{queries:a.queries, complete:a.queries===gold.queries.length,
      recallAtK:a.recall/a.queries, mrr:a.mrr/a.queries, ndcgAtK:a.ndcg/a.queries,
      citationStructureRate:a.citations?a.structurallyValidCitations/a.citations:null}]))};
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [goldPath, predictionsPath, k='5'] = process.argv.slice(2);
  if (!goldPath || !predictionsPath) throw Error('usage: node scripts/evaluate-rag.mjs gold.json predictions.jsonl [k]');
  const raw=readFileSync(predictionsPath,'utf8');if(raw.length>10_000_000)throw Error('Prediction file exceeds bound');
  const predictions=raw.split('\n').filter(s=>s.trim()).map(s=>JSON.parse(s));
  console.log(JSON.stringify(evaluate(JSON.parse(readFileSync(goldPath,'utf8')),predictions,Number(k)),null,2));
}
