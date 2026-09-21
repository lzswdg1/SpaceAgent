import { END, START, StateGraph, StateSchema, type GraphNode } from "@langchain/langgraph";
import { createHash } from "node:crypto";
import { z } from "zod";
import { GraphV2RequestSchema, GraphV2ResultSchema, type GraphV2Request, type GraphV2Result } from "./graph-v2-contracts.js";

const RouteSchema=z.enum(["handoff","wait","complete"]); type Route=z.infer<typeof RouteSchema>;
const State=new StateSchema({request:GraphV2RequestSchema,route:RouteSchema.optional(),result:GraphV2ResultSchema.optional()});
const commandId=(r:GraphV2Request,kind:string)=>`${r.graphSessionId}:${r.cursor.sequence+1}:${kind}`;
const canonical=(value:unknown):unknown=>Array.isArray(value)?value.map(canonical):value!==null&&typeof value==="object"?Object.fromEntries(Object.entries(value as Record<string,unknown>).sort(([left],[right])=>left.localeCompare(right)).map(([key,nested])=>[key,canonical(nested)])):value;
export function graphV2CommandInputHash(r:GraphV2Request,kind:GraphV2Result["command"]["kind"],payload:Record<string,unknown>):string{const body=`${r.graphSessionId}\n${r.cursor.sequence}\n${kind}\n${JSON.stringify(canonical(payload))}`;return `sha256:${createHash("sha256").update(body,"utf8").digest("hex")}`;}
function result(r:GraphV2Request,kind:GraphV2Result["command"]["kind"],payload:Record<string,unknown>):GraphV2Result{const id=commandId(r,kind);return GraphV2ResultSchema.parse({contractVersion:"graph/v2",requestId:r.requestId,graphSessionId:r.graphSessionId,bundleHash:r.bundleHash,nextCursor:{sequence:r.cursor.sequence+1,completedCommandIds:r.cursor.completedCommandIds,pendingCommandId:id},command:{commandId:id,kind,inputHash:graphV2CommandInputHash(r,kind,payload),payload},ephemeral:true});}
const supervisor:GraphNode<typeof State>=(state)=>({route:state.request.cursor.pendingCommandId!==null?"wait":state.request.limits.maxAgents>1&&state.request.limits.maxDepth>1?"handoff":"complete"});
const handoff:GraphNode<typeof State>=(state)=>({result:result(state.request,"HANDOFF_PROPOSED",{reason:"Pure graph proposal; Java validates and executes any handoff boundary."})});
const wait:GraphNode<typeof State>=(state)=>({result:result(state.request,"WAIT_FOR_APPROVAL",{pendingCommandId:state.request.cursor.pendingCommandId})});
const complete:GraphNode<typeof State>=(state)=>({result:result(state.request,"COMPLETED",{summary:"No eligible graph subcommand remains."})});
export const graphV2=new StateGraph(State).addNode("supervisor",supervisor).addNode("handoff",handoff).addNode("wait",wait).addNode("complete",complete).addEdge(START,"supervisor").addConditionalEdges("supervisor",s=>s.route??"complete",{handoff:"handoff",wait:"wait",complete:"complete"}).addEdge("handoff",END).addEdge("wait",END).addEdge("complete",END).compile();
export async function transitionGraphV2(request:GraphV2Request):Promise<GraphV2Result>{const state=await graphV2.invoke({request});if(state.result===undefined)throw new Error("graph/v2 completed without proposal");return GraphV2ResultSchema.parse(state.result);}
