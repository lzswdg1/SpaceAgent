import { test } from 'node:test';
import assert from 'node:assert/strict';
import { evaluate } from './evaluate-rag.mjs';
const gold={documents:[{id:'a'},{id:'b'}],queries:[{id:'q',relevantDocumentIds:['a']}]};
test('metrics distinguish incorrect ranking, duplicate hits and missing evidence',()=>{
  const r=evaluate(gold,[{id:'q',mode:'dense',hits:[{documentId:'b'},{documentId:'b'},{documentId:'a',chunkId:'chunk',contentHash:'a'.repeat(64)}]}]);
  assert.equal(r.modes.dense.recallAtK,1);assert.equal(r.modes.dense.mrr,.5);assert.equal(r.modes.dense.ndcgAtK,1/Math.log2(3));
  assert.equal(r.modes.dense.citationStructureRate,1/3);assert.equal(r.qualityAcceptance,'EXTERNAL_ACCEPTANCE_PENDING');
});
test('unknown query or repeated mode/query fails rather than inflating metrics',()=>{
  const row={id:'q',mode:'hybrid',hits:[]};assert.throws(()=>evaluate(gold,[row,row]));assert.throws(()=>evaluate(gold,[{...row,id:'unknown'}]));
  assert.equal(evaluate(gold,[row]).modes.hybrid.recallAtK,0);
});
