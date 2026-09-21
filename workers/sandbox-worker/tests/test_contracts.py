import json
import unittest
from pathlib import Path

from sandbox_worker.contracts import (
    SandboxExecutionRequest,
    SandboxExecutionResponse,
    request_from_dict,
    response_from_dict,
)


ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / "contracts" / "fixtures"


class SandboxContractTests(unittest.TestCase):
    def test_request_round_trips_golden_fixture(self):
        data = json.loads((FIXTURES / "sandbox" / "request.json").read_text())
        request = request_from_dict(data)
        self.assertIsInstance(request, SandboxExecutionRequest)
        self.assertEqual("run-1", request.agentRunId)
        self.assertEqual("call-1", request.toolCallId)
        self.assertEqual("echo", request.command)
        self.assertFalse(request.resourcePolicy.allowNetwork)
        self.assertIsNone(request.sourceRef)

    def test_response_round_trips_golden_fixture(self):
        data = json.loads((FIXTURES / "sandbox" / "response.json").read_text())
        response = response_from_dict(data)
        self.assertIsInstance(response, SandboxExecutionResponse)
        self.assertEqual("SUCCEEDED", response.status)
        self.assertEqual(0, response.exitStatus)
        self.assertFalse(response.metadata.timedOut)


if __name__ == "__main__":
    unittest.main()
