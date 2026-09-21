import type {useChatRun} from './useChatRun'
import './chat-run-panel.css'
export function ChatRunPanel({run,tx}:{run:ReturnType<typeof useChatRun>;tx:(key:string)=>string}){
  const status=run.status,state=status?.executionState
  if(!status&&!run.error)return null
  const waiting=state==='WAITING_APPROVAL'||state==='WAITING_PLAN_APPROVAL'
  const label=state==='CANCELLING'?'runStopping':state==='CANCELLED'?'runCancelled':state==='FAILED'?'runFailed':waiting?'runWaitingApproval'
    :state==='WAITING_RECONCILIATION'?'runWaitingReconciliation':state==='COMPLETED'?'runFinished':'runInBackground'
  if(state==='COMPLETED'&&!status?.partial&&!run.error)return null
  return <section className="chat-run-panel" aria-live="polite"><b>{tx(label)}</b>{status?.toolName&&<span>{status.toolName}</span>}
    {status?.partial&&<p>{tx('partialAnswerSaved')}</p>}{Boolean(status?.uncertainCalls)&&<p>{tx('runUncertainOutcome')}</p>}
    {run.plan&&<ol>{run.plan.steps.map(step=><li key={step.stepKey}>{step.expectedOutput}</li>)}</ol>}
    {run.error&&<p role="alert">{run.error}</p>}
    {waiting&&<div><button disabled={run.busy} onClick={()=>void run.act('reject')}>{tx('reject')}</button><button disabled={run.busy||(state==='WAITING_PLAN_APPROVAL'&&!run.plan)} onClick={()=>void run.act('approve')}>{tx('approveAndResume')}</button></div>}
    {state==='WAITING_RECONCILIATION'&&<button disabled={run.busy} onClick={()=>void run.act('reconcile')}>{tx('reconcileAndResume')}</button>}
    {run.active&&!waiting&&state!=='CANCELLING'&&<button disabled={run.busy} onClick={()=>void run.act('cancel')}>{tx('stop')}</button>}
    <button disabled={run.busy} onClick={run.refresh}>{tx('refresh')}</button>
  </section>
}
