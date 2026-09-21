import {
  SpanKind,
  SpanStatusCode,
  context,
  trace,
  type Context,
  type TextMapGetter,
} from "@opentelemetry/api";
import { W3CTraceContextPropagator } from "@opentelemetry/core";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { NodeSDK } from "@opentelemetry/sdk-node";

export const GEN_AI_SEMCONV_COMMIT = "94f432d7126f5884d30a2cdde6f4e89908ebb6fd";
const propagator = new W3CTraceContextPropagator();
const getter: TextMapGetter<Record<string, string | string[] | undefined>> = {
  keys(carrier) { return Object.keys(carrier); },
  get(carrier, key) { return carrier[key.toLowerCase()]; },
};
let sdk: NodeSDK | undefined;

export async function startTelemetry(environment: NodeJS.ProcessEnv = process.env): Promise<void> {
  if (environment.OTEL_SDK_DISABLED === "true"
      || environment.PLATFORM_OBSERVABILITY_OTLP_ENABLED !== "true") return;
  if (sdk) return;
  const endpoint = environment.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
    ?? environment.PLATFORM_OBSERVABILITY_OTLP_ENDPOINT;
  if (!endpoint) return;
  try {
    const candidate = new NodeSDK({
      traceExporter: new OTLPTraceExporter({ url: endpoint }),
      textMapPropagator: propagator,
      serviceName: "multi-agent-orchestrator",
    });
    candidate.start();
    sdk = candidate;
  } catch {
    sdk = undefined;
  }
}

export async function shutdownTelemetry(): Promise<void> {
  const current = sdk;
  sdk = undefined;
  if (current) await current.shutdown();
}

export async function withOrchestrationSpan<T>(
  headers: Record<string, string | string[] | undefined>,
  operation: () => Promise<T>,
): Promise<T> {
  const parent = extractContext(headers);
  const tracer = trace.getTracer("spaceagent.multi-agent", "0.1.0");
  return context.with(parent, () => tracer.startActiveSpan(
    "invoke_workflow spaceagent.multi_agent",
    {
      kind: SpanKind.INTERNAL,
      attributes: {
        "gen_ai.operation.name": "invoke_workflow",
        "gen_ai.workflow.name": "spaceagent.multi_agent",
        "spaceagent.contract.version": "multi-agent/v1",
        "spaceagent.gen_ai.semconv.commit": GEN_AI_SEMCONV_COMMIT,
      },
    },
    async (span) => {
      try {
        const result = await operation();
        span.setStatus({ code: SpanStatusCode.OK });
        return result;
      } catch (cause) {
        span.setAttribute("error.type", safeErrorType(cause));
        span.setStatus({ code: SpanStatusCode.ERROR });
        throw cause;
      } finally {
        span.end();
      }
    },
  ));
}

export function extractedTraceId(
  headers: Record<string, string | string[] | undefined>,
): string | undefined {
  return trace.getSpanContext(extractContext(headers))?.traceId;
}

function extractContext(headers: Record<string, string | string[] | undefined>): Context {
  return propagator.extract(context.active(), headers, getter);
}

function safeErrorType(cause: unknown): string {
  if (!(cause instanceof Error)) return "_OTHER";
  const value = cause.name.trim();
  return /^[A-Za-z0-9_.-]{1,120}$/.test(value) ? value : "_OTHER";
}
