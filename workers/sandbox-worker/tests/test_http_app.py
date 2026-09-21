import json
import threading
import unittest
from http.client import HTTPConnection

from sandbox_worker.contracts import ExecutionMetadata, SandboxExecutionResponse
from sandbox_worker.http_app import make_server
from sandbox_worker.telemetry import (
    GEN_AI_SEMCONV_COMMIT,
    configure_telemetry,
    extracted_trace_id,
)


class StubSandboxExecutionEngine:
    def capabilities(self):
        return {"status": "ok", "engine": "docker", "publicUntrustedReady": False}

    def execute(self, request):
        return SandboxExecutionResponse(
            executionId=request.executionId,
            agentRunId=request.agentRunId,
            toolCallId=request.toolCallId,
            exitStatus=0,
            status="SUCCEEDED",
            stdout="hello\n",
            stderr="",
            artifactRefs=[],
            metadata=ExecutionMetadata(1, 2, 1, False, 6),
        )


class SandboxHttpAppTests(unittest.TestCase):
    token = "sandbox-test-internal-token-0123456789"

    @classmethod
    def setUpClass(cls):
        cls.executor = StubSandboxExecutionEngine()
        cls.server = make_server(
            cls.executor, "127.0.0.1", 0, internal_token=cls.token
        )
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def test_execute_endpoint(self):
        payload = {
            "executionId": "exec-1",
            "agentRunId": "run-1",
            "toolCallId": "call-1",
            "workspaceRef": ".",
            "taskRef": "task-1",
            "tool": "sandbox-bash",
            "command": "echo",
            "arguments": ["hello"],
            "timeoutSeconds": 5,
            "resourcePolicy": {
                "maxOutputBytes": 65536,
                "maxCpuSeconds": 2,
                "maxMemoryBytes": 268435456,
                "allowNetwork": False,
                "allowedPaths": ["."],
            },
            "environment": "{}",
        }
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request(
            "POST",
            "/execute",
            body=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.token,
            },
        )
        response = connection.getresponse()
        data = json.loads(response.read().decode("utf-8"))
        connection.close()
        self.assertEqual(200, response.status)
        self.assertEqual("SUCCEEDED", data["status"])
        self.assertEqual("hello\n", data["stdout"])

    def test_execute_requires_internal_token(self):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request(
            "POST",
            "/execute",
            body=b"{}",
            headers={"Content-Type": "application/json"},
        )
        response = connection.getresponse()
        response.read()
        connection.close()
        self.assertEqual(401, response.status)

    def test_health_exposes_only_bounded_capabilities(self):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        connection.request("GET", "/health")
        response = connection.getresponse()
        data = json.loads(response.read().decode("utf-8"))
        connection.close()
        self.assertEqual(200, response.status)
        self.assertEqual("docker", data["engine"])
        self.assertNotIn("workspace", json.dumps(data).lower())

    def test_w3c_trace_context_is_extracted_without_payload_capture(self):
        trace_id = "0af7651916cd43dd8448eb211c80319c"
        self.assertEqual(
            trace_id,
            extracted_trace_id(
                {"traceparent": f"00-{trace_id}-b7ad6b7169203331-01"}
            ),
        )
        self.assertIsNone(extracted_trace_id({"traceparent": "invalid"}))
        self.assertRegex(GEN_AI_SEMCONV_COMMIT, r"^[0-9a-f]{40}$")
        configure_telemetry(
            {
                "PLATFORM_OBSERVABILITY_OTLP_ENABLED": "true",
                "PLATFORM_OBSERVABILITY_OTLP_ENDPOINT": "://invalid",
            }
        )


if __name__ == "__main__":
    unittest.main()
