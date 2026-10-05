# Scripted ACP agent for the tasks end-to-end test: asks to push to main (the safety
# net must refuse it without asking), then asks to delete a folder (high risk, the
# test approves), then writes result.txt in its folder and replies.
import json,os,sys,threading
def send(m): sys.stdout.write(json.dumps(m)+"\n"); sys.stdout.flush()
pending={}; nid=[100]; sid="s1"; cwd=[os.getcwd()]
def upd(u): send({"jsonrpc":"2.0","method":"session/update","params":{"sessionId":sid,"update":u}})
def ask(tid,title,cmd):
    nid[0]+=1; pid=nid[0]; ev=threading.Event(); pending[pid]=ev
    send({"jsonrpc":"2.0","id":pid,"method":"session/request_permission","params":{"sessionId":sid,"toolCall":{"toolCallId":tid,"title":title,"kind":"execute","rawInput":{"command":cmd}},"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"},{"optionId":"no","name":"Reject","kind":"reject_once"}]}})
    ev.wait(); return ev.answer
def turn(rid,prompt):
    a1=ask("t1","Push","git push origin main")
    a2=ask("t2","Clean build","rm -rf build")
    if a2.get("outcome",{}).get("optionId")=="allow":
        with open(os.path.join(cwd[0],"result.txt"),"w") as f: f.write("done\n")
    rules="Working rules from Hypurr" in prompt
    upd({"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"push=%s rm=%s rules=%s"%(a1.get("outcome",{}).get("optionId"),a2.get("outcome",{}).get("optionId"),rules)}})
    send({"jsonrpc":"2.0","id":rid,"result":{"stopReason":"end_turn"}})
for line in sys.stdin:
    m=json.loads(line)
    if "method" not in m:
        ev=pending.pop(m["id"],None)
        if ev: ev.answer=m.get("result",{}); ev.set()
        continue
    meth=m["method"]
    if meth=="initialize": send({"jsonrpc":"2.0","id":m["id"],"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":False}}})
    elif meth=="session/new":
        cwd[0]=m["params"].get("cwd",cwd[0]); send({"jsonrpc":"2.0","id":m["id"],"result":{"sessionId":sid}})
    elif meth=="session/prompt":
        text="".join(b.get("text","") for b in m["params"].get("prompt",[]) if isinstance(b,dict))
        threading.Thread(target=turn,args=(m["id"],text)).start()
    elif meth=="session/cancel":
        for k,ev in list(pending.items()): ev.answer={"outcome":{"outcome":"cancelled"}}; ev.set()
