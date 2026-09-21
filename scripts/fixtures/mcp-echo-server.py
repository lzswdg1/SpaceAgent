#!/usr/bin/env python3

import json
import sys


def read_message():
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            return None
        stripped = line.strip()
        if not stripped:
            if headers:
                break
            continue
        if stripped.startswith(b"{"):
            return json.loads(stripped)
        name, _, value = stripped.partition(b":")
        headers[name.decode("ascii").lower()] = value.decode("ascii").strip()

    content_length = int(headers.get("content-length", "0"))
    if content_length <= 0:
        return None
    return json.loads(sys.stdin.buffer.read(content_length))


def write_message(message):
    body = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    sys.stdout.buffer.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii"))
    sys.stdout.buffer.write(body)
    sys.stdout.buffer.flush()


def response(request_id, result):
    write_message({"jsonrpc": "2.0", "id": request_id, "result": result})


def main():
    while True:
        request = read_message()
        if request is None:
            return
        method = request.get("method")
        request_id = request.get("id")
        if method == "notifications/initialized":
            continue
        if method == "initialize":
            response(request_id, {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "spaceagent-echo", "version": "1.0.0"},
            })
        elif method == "tools/list":
            response(request_id, {"tools": [{
                "name": "echo",
                "description": "Echo the supplied message exactly. Use this when the user explicitly asks to call the echo tool.",
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        "message": {"type": "string", "description": "Message to echo"}
                    },
                    "required": ["message"],
                    "additionalProperties": False,
                },
            }]})
        elif method == "tools/call":
            arguments = (request.get("params") or {}).get("arguments") or {}
            message = str(arguments.get("message", ""))
            response(request_id, {
                "content": [{"type": "text", "text": message}],
                "structuredContent": {"echo": message},
                "isError": False,
            })
        else:
            write_message({
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"},
            })


if __name__ == "__main__":
    main()
