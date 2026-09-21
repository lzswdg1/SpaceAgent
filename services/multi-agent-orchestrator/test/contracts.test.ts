import assert from "node:assert/strict";
import test from "node:test";

import { OrchestrationRequestSchema, parseRequest, parseResponse } from "../src/contracts.js";
import { fixture } from "./fixtures.js";

test("all golden fixtures satisfy strict Zod contracts", async () => {
  for (const route of ["plan", "delegate", "handoff", "review", "approval", "complete"]) {
    parseRequest(await fixture(`request-${route}.json`));
    parseResponse(await fixture(`response-${route}.json`));
  }
  parseRequest(await fixture("request-provider-reasoning.json"));
  parseResponse(await fixture("response-model.json"));
});

test("request rejects unknown fields and credential-shaped additions", async () => {
  const valid = await fixture("request-plan.json") as Record<string, unknown>;
  assert.equal(OrchestrationRequestSchema.safeParse({ ...valid, apiKey: "secret" }).success, false);
  const inference = { ...valid, baseUrl: "https://provider.example", providerSecret: "secret" };
  assert.equal(OrchestrationRequestSchema.safeParse(inference).success, false);
});
