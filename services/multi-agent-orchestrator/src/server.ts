import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

import { ZodError } from "zod";

import { parseRequest } from "./contracts.js";
import { orchestrate, ReasoningDecisionError } from "./graph.js";
import { timingSafeEqual } from "node:crypto";
import { withOrchestrationSpan } from "./telemetry.js";
import { parseGraphV2Request } from "./graph-v2-contracts.js";
import { transitionGraphV2 } from "./graph-v2.js";

const MAX_BODY_BYTES = 1_048_576;

type ErrorCode = "MALFORMED_JSON" | "VALIDATION_ERROR" | "PAYLOAD_TOO_LARGE"
  | "REASONING_RESULT_INVALID" | "UNAUTHORIZED" | "INTERNAL_ERROR";

function json(response: ServerResponse, status: number, body: unknown): void {
  const value = JSON.stringify(body);
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(value),
  });
  response.end(value);
}

function error(response: ServerResponse, status: number, code: ErrorCode, message: string): void {
  json(response, status, { code, message });
}

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > MAX_BODY_BYTES) {
      throw new PayloadTooLargeError();
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks).toString("utf8");
}

class PayloadTooLargeError extends Error {}

function authorized(request: IncomingMessage, internalToken: string): boolean {
  const supplied = request.headers.authorization ?? "";
  const expected = `Bearer ${internalToken}`;
  const left = Buffer.from(supplied);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

async function handle(
  request: IncomingMessage,
  response: ServerResponse,
  internalToken: string,
): Promise<void> {
  if (request.method === "GET" && request.url === "/health") {
    json(response, 200, {
      status: "UP",
      framework: "LANGGRAPH_JS",
      durableState: false,
      sideEffects: false,
    });
    return;
  }
  if (request.method !== "POST" || (request.url !== "/v1/orchestrate" && request.url !== "/v1/graph-v2")) {
    error(response, 404, "VALIDATION_ERROR", "Route not found");
    return;
  }
  if (!internalToken || !authorized(request, internalToken)) {
    error(response, 401, "UNAUTHORIZED", "Unauthorized");
    return;
  }
  try {
    const raw = await readBody(request);
    let value: unknown;
    try {
      value = JSON.parse(raw);
    } catch {
      error(response, 400, "MALFORMED_JSON", "Request body must be valid JSON");
      return;
    }
    const command = request.url === "/v1/graph-v2"
      ? await withOrchestrationSpan(request.headers,
        () => transitionGraphV2(parseGraphV2Request(value)))
      : await withOrchestrationSpan(request.headers,
        () => orchestrate(parseRequest(value)));
    json(response, 200, command);
  } catch (cause) {
    if (cause instanceof PayloadTooLargeError) {
      error(response, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds 1048576 bytes");
    } else if (cause instanceof ZodError) {
      error(response, 400, "VALIDATION_ERROR", "Request does not match the selected orchestration contract");
    } else if (cause instanceof ReasoningDecisionError) {
      error(response, 502, "REASONING_RESULT_INVALID", "Provider reasoning result was rejected");
    } else {
      error(response, 500, "INTERNAL_ERROR", "Orchestration failed");
    }
  }
}

export function createOrchestrationServer(internalToken = ""): Server {
  return createServer((request, response) => {
    void handle(request, response, internalToken);
  });
}
