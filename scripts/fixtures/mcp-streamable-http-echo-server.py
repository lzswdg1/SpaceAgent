#!/usr/bin/env python3
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class McpHandler(BaseHTTPRequestHandler):
    server_version = "SpaceAgentMcpFixture/1.0"

    def do_POST(self):
        if self.path != "/mcp":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != f"Bearer {self.server.auth_token}":
            self.send_error(401)
            return

        length = int(self.headers.get("Content-Length", "0"))
        try:
            request = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self.send_error(400)
            return

        method = request.get("method")
        request_id = request.get("id")
        self._record(request)
        if method == "initialize":
            capabilities = {"tools": {}, "resources": {}, "prompts": {}}
            if self.server.advertise_tasks:
                capabilities["tasks"] = {
                    "list": {}, "cancel": {}, "requests": {"tools": {"call": {}}}}
            self._json_response({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": capabilities,
                    "serverInfo": {"name": "spaceagent-http-echo", "version": "1.0.0"},
                },
            }, session=True)
            return
        if method == "notifications/initialized":
            self.send_response(202)
            self.end_headers()
            return
        if method == "tools/list":
            message = {
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {
                    "tools": [{
                        "name": "echo",
                        "description": "Echo a message",
                        "inputSchema": {
                            "type": "object",
                            "properties": {"message": {"type": "string"}},
                            "required": ["message"],
                        },
                    }, {
                        "name": "issue_write",
                        "description": "Update an issue",
                        "inputSchema": {"type": "object"},
                        "annotations": {"readOnlyHint": False, "destructiveHint": True},
                    }, {
                        "name": "issue_read",
                        "description": "Read an issue",
                        "inputSchema": {"type": "object"},
                        "annotations": {"readOnlyHint": True, "destructiveHint": False},
                    }],
                },
            }
            self._sse_response(message)
            return
        if method == "tools/call":
            params = request.get("params") or {}
            if params.get("name") == "issue_read":
                arguments = params.get("arguments") or {}
                if arguments != {"method": "get", "owner": "octocat", "repo": "demo", "issue_number": 42}:
                    self.send_error(400)
                    return
                issue = {
                    "number": 42,
                    "title": "Verified title",
                    "body": "Verified body",
                    "state": "open",
                    "html_url": "https://github.com/octocat/demo/issues/42",
                }
                self._json_response({
                    "jsonrpc": "2.0", "id": request_id,
                    "result": {"content": [{"type": "text", "text": json.dumps(issue)}]},
                })
                return
            if params.get("name") != "echo":
                self._json_response({
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32601, "message": "Unknown tool"},
                })
                return
            message = (params.get("arguments") or {}).get("message", "")
            if self.server.task_result:
                self._json_response({
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "taskId": "fixture-remote-task",
                        "status": "working",
                        "statusMessage": "queued",
                        "createdAt": "2026-09-08T00:00:00Z",
                        "lastUpdatedAt": "2026-09-08T00:00:00Z",
                        "ttl": 60000,
                        "pollInterval": 1000,
                    },
                })
                return
            self._json_response({
                "jsonrpc": "2.0",
                "id": request_id,
                "result": {"content": [{"type": "text", "text": message}]},
            })
            return
        if method == "resources/list":
            self._json_response({"jsonrpc": "2.0", "id": request_id, "result": {"resources": [{
                "uri": "resource://fixture/readme", "name": "readme", "title": "Fixture README",
                "description": "Safe fixture", "mimeType": "text/plain", "size": 13}]}})
            return
        if method == "resources/read":
            if (request.get("params") or {}).get("uri") != "resource://fixture/readme":
                self.send_error(404)
                return
            self._json_response({"jsonrpc": "2.0", "id": request_id, "result": {"contents": [{
                "uri": "resource://fixture/readme", "mimeType": "text/plain", "text": "fixture readme"}]}})
            return
        if method == "prompts/list":
            self._json_response({"jsonrpc": "2.0", "id": request_id, "result": {"prompts": [{
                "name": "summarize", "title": "Summarize", "description": "Summarize a topic",
                "arguments": [{"name": "topic", "description": "Topic to summarize", "required": True}]}]}})
            return
        if method == "prompts/get":
            params = request.get("params") or {}
            topic = (params.get("arguments") or {}).get("topic")
            if params.get("name") != "summarize" or not topic:
                self.send_error(400)
                return
            self._json_response({"jsonrpc": "2.0", "id": request_id, "result": {
                "description": "Fixture prompt", "messages": [{
                    "role": "user", "content": {"type": "text", "text": f"Summarize {topic}"}}]}})
            return
        self.send_error(400)

    def do_DELETE(self):
        if self.path != "/mcp":
            self.send_error(404)
            return
        self._json_response({})

    def log_message(self, format_string, *args):
        return

    def _record(self, request):
        if self.server.request_log is None:
            return
        line = json.dumps(request, ensure_ascii=True, sort_keys=True) + "\n"
        with self.server.request_log_lock:
            with self.server.request_log.open("a", encoding="utf-8") as output:
                output.write(line)

    def _json_response(self, payload, session=False):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if session:
            self.send_header("Mcp-Session-Id", "spaceagent-fixture-session")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _sse_response(self, payload):
        body = f"event: message\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--request-log")
    parser.add_argument("--advertise-tasks", action="store_true")
    parser.add_argument("--task-result", action="store_true")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), McpHandler)
    server.auth_token = args.token
    server.request_log = Path(args.request_log) if args.request_log else None
    server.request_log_lock = threading.Lock()
    server.advertise_tasks = args.advertise_tasks
    server.task_result = args.task_result
    Path(args.port_file).write_text(str(server.server_port), encoding="utf-8")
    server.serve_forever()


if __name__ == "__main__":
    main()
