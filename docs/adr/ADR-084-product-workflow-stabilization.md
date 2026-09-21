# Product workflow stabilization

User-facing workflows must preserve object identity, code lineage and recoverable outcome across
retries, context switches, disconnections and service restarts. Java/PostgreSQL remain authoritative;
clients project that state rather than inventing completion.

## Git references

Remote observations live under refs/remotes/origin/*; refs/heads/* are retained local source branches.
Fetching must never force-update local heads. An upstream head may initialize a missing local branch,
or fast-forward a local branch that is an ancestor. Diverged/reviewed local commits remain intact.
Legacy mirror repositories are converted lazily by replacing only fetch configuration and disabling
mirror mode. Existing heads and objects are not removed. There is no remote push in this repair.

New merge intents select their target from the Workspace's named base branch, not from the mutable
SourceRepository default. The existing merge row pins targetRef and expected commit for CAS/recovery;
replays continue to use that immutable row. A bare commit identifier is not a named merge target.

## User/runtime boundary

Tenant changes invalidate browser-local state and outstanding responses. Object editors apply results
only to the initiating object. Completion requires a terminal stream/Run outcome; partial content,
approval waits, cancellation and UNKNOWN must remain distinguishable and recoverable. Disconnected
clients may query state, but must not blindly replay paid or durable operations.

## Durable launch and completion

Integration owns only Project startup workflow receipts (V1084), not Project/Task/Runtime truth.
Coding admission composes public owner APIs in one PostgreSQL transaction. A hashed owner-scoped
idempotency key and normalized input hash return the same result; a failed transaction leaves no
partial Task/Plan/Agent mutation. Remote GitHub root setup is resumable rather than transactionally
pretending to roll back remote work: the pending receipt identifies one hidden Project and stable
import key; aliases keep a retried client key replayable after completion. Existing code is retained.
Assignments may bind either a real ModelPool or the admitted Agent's valid direct Provider/model;
there is no fabricated pool and coding/reviewer Agents remain distinct.

Conversation stores pending/partial/final message state; Runtime projects the latest owned Chat Run,
approval wait and cancellation. Disconnect does not cancel admitted work. Explicit cancellation
prevents further dispatch and retains uncertainty for already dispatched work. Reply progress is
durable before continuation, and exhaustion/failure does not masquerade as a complete answer.
Request-local continuations are bounded by the Agent/candidate context limit including instructions,
question, evidence, tool schemas and output reserve. Public recovery JSON is a redacted projection;
the authoritative internal snapshot and hash remain unchanged.

Implementation scope and evidence are tracked in `.agent/PRODUCT-STABILIZATION-PLAN.md`.
