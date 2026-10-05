# Deterministic ACP agent for the chat end-to-end test: answers every prompt with a
# short Markdown reply that names who it is and what it was asked (last line of the prompt).
import json, os, sys, threading

NAME = os.environ.get("CHAT_AGENT_NAME") or os.path.basename(os.getcwd()).capitalize()
sessions = [0]


def send(m):
    sys.stdout.write(json.dumps(m) + "\n")
    sys.stdout.flush()


def text_of(prompt):
    return "\n".join(b.get("text", "") for b in prompt if b.get("type") == "text")


def turn(rid, sid, prompt):
    asked = [l for l in text_of(prompt).strip().splitlines() if l.strip()]
    last = asked[-1] if asked else ""
    files = "Attached files" in text_of(prompt)
    reply = (
        f"**{NAME}** here. You said: _{last[:80]}_\n\n"
        "- run `npm test`\n- check [the docs](https://example.com/docs)\n\n"
        "```sh\nnpm test\n```"
        + ("\n\nI can see the attached file." if files else "")
    )
    send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": sid, "update": {
        "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": reply}}}})
    send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "end_turn"}})


for line in sys.stdin:
    m = json.loads(line)
    if "method" not in m:
        continue
    meth, p = m["method"], m.get("params", {})
    if meth == "initialize":
        send({"jsonrpc": "2.0", "id": m["id"], "result": {"protocolVersion": 1, "agentCapabilities": {"loadSession": False}}})
    elif meth == "session/new":
        sessions[0] += 1
        send({"jsonrpc": "2.0", "id": m["id"], "result": {"sessionId": f"s{sessions[0]}"}})
    elif meth == "session/prompt":
        threading.Thread(target=turn, args=(m["id"], p.get("sessionId", "s1"), p.get("prompt", []))).start()
    elif "id" in m:
        send({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": "not supported"}})
