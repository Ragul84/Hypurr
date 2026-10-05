#!/usr/bin/env python3
"""Fake Hypurr Agent ACP agent: free Zen models + a short reply. Used by host e2e."""
import json, sys

def send(msg):
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()

def main():
    session = None
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        req = json.loads(line)
        mid, method, params = req.get("id"), req.get("method"), req.get("params") or {}
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": 1,
                "agentCapabilities": {"loadSession": False},
                "agentInfo": {"name": "Hypurr Agent", "version": "test"},
                "authMethods": [],
            }})
        elif method == "authenticate":
            send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/new":
            session = "s1"
            send({"jsonrpc": "2.0", "id": mid, "result": {
                "sessionId": session,
                "configOptions": [{
                    "id": "model", "type": "select", "category": "model",
                    "currentValue": "opencode/big-pickle",
                    "options": [
                        {"value": "opencode/big-pickle", "name": "Big Pickle"},
                        {"value": "opencode/space-bunny-free", "name": "Space Bunny Free"},
                    ],
                }],
            }})
        elif method == "session/prompt":
            send({"jsonrpc": "2.0", "method": "session/update", "params": {
                "sessionId": session, "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": "HELLO_HYPURR from Hypurr Agent free model."},
                }}})
            send({"jsonrpc": "2.0", "id": mid, "result": {
                "stopReason": "end_turn",
                "usage": {"inputTokens": 10, "outputTokens": 8, "totalTokens": 18},
            }})
        elif method == "session/set_config_option" or method == "session/set_model":
            send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/cancel":
            send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif mid is not None:
            send({"jsonrpc": "2.0", "id": mid, "result": {}})

if __name__ == "__main__":
    main()
