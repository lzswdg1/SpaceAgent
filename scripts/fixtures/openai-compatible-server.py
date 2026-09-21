#!/usr/bin/env python3
"""Deterministic OpenAI-compatible chat and embedding fixture for local E2E."""

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    server_version = "SpaceAgentOpenAIFixture/1.0"

    def do_GET(self):
        if self.path in ("/health", "/v1/models"):
            payload = (
                {"status": "UP"}
                if self.path == "/health"
                else {"object": "list", "data": [{"id": "qwen-plus", "object": "model"}]}
            )
            self._json(200, payload)
            return
        self._json(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        request = self._read_json()
        if request is None:
            return
        if self.path == "/v1/chat/completions":
            if request.get("stream"):
                self._stream_chat()
            else:
                messages = request.get("messages", [])
                latest_message = messages[-1].get("content", "") if messages else ""
                if "tool:echo-replay " in latest_message:
                    value = self._marker_value(latest_message, "tool:echo-replay ")
                    self._tool_completion(
                        request,
                        "call-regression-replay",
                        [value, value],
                    )
                    return
                if "tool:echo-conflict " in latest_message:
                    value = self._marker_value(latest_message, "tool:echo-conflict ")
                    self._tool_completion(
                        request,
                        "call-regression-conflict",
                        [value, value + "-different-input"],
                    )
                    return
                if "tool:echo " in latest_message:
                    value = self._marker_value(latest_message, "tool:echo ")
                    self._tool_completion(request, "call-regression-echo", [value])
                    return
                self._json(
                    200,
                    {
                        "id": "chatcmpl-spaceagent-e2e",
                        "object": "chat.completion",
                        "created": int(time.time()),
                        "model": request.get("model", "qwen-plus"),
                        "choices": [
                            {
                                "index": 0,
                                "message": {
                                    "role": "assistant",
                                    "content": "SpaceAgent E2E reply",
                                },
                                "finish_reason": "stop",
                            }
                        ],
                        "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 5,
                            "total_tokens": 17,
                        },
                    },
                )
            return
        if self.path == "/v1/embeddings":
            raw_input = request.get("input", "")
            inputs = raw_input if isinstance(raw_input, list) else [raw_input]
            data = []
            for index, value in enumerate(inputs):
                vector = [0.0] * 1024
                vector[abs(hash(str(value))) % len(vector)] = 1.0
                data.append({"object": "embedding", "index": index, "embedding": vector})
            self._json(
                200,
                {
                    "object": "list",
                    "model": request.get("model", "text-embedding-v4"),
                    "data": data,
                    "usage": {"prompt_tokens": len(inputs), "total_tokens": len(inputs)},
                },
            )
            return
        self._json(404, {"error": {"message": "Not found"}})

    def _marker_value(self, message, marker):
        return message.split(marker, 1)[1].splitlines()[0]

    def _tool_completion(self, request, call_id, values):
        tool_calls = [
            {
                "id": call_id,
                "type": "function",
                "function": {
                    "name": "echo",
                    "arguments": json.dumps({"text": value}),
                },
            }
            for value in values
        ]
        self._json(
            200,
            {
                "id": "chatcmpl-spaceagent-tool-e2e",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": request.get("model", "qwen-plus"),
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": tool_calls,
                        },
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 3,
                    "total_tokens": 15,
                },
            },
        )

    def _stream_chat(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        chunks = [
            {
                "id": "chatcmpl-spaceagent-e2e",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "qwen-plus",
                "choices": [
                    {
                        "index": 0,
                        "delta": {"role": "assistant", "content": "SpaceAgent "},
                        "finish_reason": None,
                    }
                ],
            },
            {
                "id": "chatcmpl-spaceagent-e2e",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "qwen-plus",
                "choices": [
                    {
                        "index": 0,
                        "delta": {"content": "E2E reply"},
                        "finish_reason": None,
                    }
                ],
            },
            {
                "id": "chatcmpl-spaceagent-e2e",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "qwen-plus",
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 5,
                    "total_tokens": 17,
                },
            },
        ]
        for chunk in chunks:
            self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
            self.wfile.flush()
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _read_json(self):
        try:
            return json.loads(self._read_body() or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._json(400, {"error": {"message": "Invalid JSON"}})
            return None

    def _read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            length = int(self.headers.get("Content-Length", "0"))
            return self.rfile.read(length)

        chunks = []
        while True:
            size_line = self.rfile.readline().strip()
            size = int(size_line.split(b";", 1)[0], 16)
            if size == 0:
                while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                    pass
                return b"".join(chunks)
            chunks.append(self.rfile.read(size))
            self.rfile.read(2)

    def _json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18080)
    args = parser.parse_args()
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
