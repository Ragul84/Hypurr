# Scripted ACP agent for the workplace end-to-end test. First prompt: checks it got the
# attached screenshot and the issue text, writes fix.txt, reports a cost and tokens.
# The learning-mode prompt: replies with a plain summary.
import json,os,sys
def send(m): sys.stdout.write(json.dumps(m)+"\n"); sys.stdout.flush()
sid="s1"; cwd=[os.getcwd()]
def upd(u): send({"jsonrpc":"2.0","method":"session/update","params":{"sessionId":sid,"update":u}})
def say(t): upd({"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":t}})
for line in sys.stdin:
    m=json.loads(line)
    if "method" not in m: continue
    meth=m["method"]
    if meth=="initialize": send({"jsonrpc":"2.0","id":m["id"],"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":False}}})
    elif meth=="session/new":
        cwd[0]=m["params"].get("cwd",cwd[0]); send({"jsonrpc":"2.0","id":m["id"],"result":{"sessionId":sid}})
    elif meth=="session/prompt":
        text="".join(b.get("text","") for b in m["params"].get("prompt",[]) if isinstance(b,dict))
        if "Learning mode" in text:
            say("- I changed fix.txt so the checkout button calls submit().\n- Why: the click handler was missing.\n- Check: click Checkout on Safari.")
            usage={"inputTokens":400,"outputTokens":100,"totalTokens":500}
        else:
            paths=[l[2:] for l in text.splitlines() if l.startswith("- /")]
            saw=any(os.path.exists(p) and p.endswith("screenshot.png") for p in paths)
            issue="Clicking it on Safari" in text
            with open(os.path.join(cwd[0],"fix.txt"),"w") as f: f.write("submit()\n")
            upd({"sessionUpdate":"usage_update","used":1500,"size":200000,"cost":{"amount":0.05,"currency":"USD"}})
            say("saw_image=%s issue=%s"%(saw,issue))
            usage={"inputTokens":1200,"outputTokens":300,"totalTokens":1500}
        send({"jsonrpc":"2.0","id":m["id"],"result":{"stopReason":"end_turn","usage":usage}})
