import { createOrchestrationServer } from "./server.js";
import { shutdownTelemetry, startTelemetry } from "./telemetry.js";

const host = process.env["HOST"] ?? "127.0.0.1";
const port = Number.parseInt(process.env["PORT"] ?? "9300", 10);
const internalToken = process.env["INTERNAL_TOKEN"] ?? "";

if (internalToken.length < 32) {
  throw new Error("INTERNAL_TOKEN must contain at least 32 characters");
}

await startTelemetry();
const server = createOrchestrationServer(internalToken);
server.listen(port, host, () => {
  process.stdout.write(`multi-agent-orchestrator listening on http://${host}:${port}\n`);
});

for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.once(signal, () => {
    server.close(() => { void shutdownTelemetry().finally(() => process.exit(0)); });
  });
}
