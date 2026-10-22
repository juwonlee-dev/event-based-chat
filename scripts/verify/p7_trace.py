import json, os, sys, time, urllib.parse, urllib.request, uuid

os.environ.setdefault("STOMP_URL", "ws://127.0.0.1:8000/ws")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import p7_cluster as base  # noqa: E402  (연결·로그인 도우미만 재사용. 장애 주입 단계는 직접 실행할 때만 돈다)
from stomp_client import on_sub  # noqa: E402

GRAFANA = "http://127.0.0.1:3000"
results = base.results


def grafana(path, params=None):
    if params:
        path += "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(GRAFANA + path) as r:
        return json.loads(r.read())


A, B = base.A, base.B
a, b, sid = base.a, base.b, base.sid
base.log(f"A → {base.nodes.get(base.ip(a.upstream))}, B → {base.nodes.get(base.ip(b.upstream))}")
base.check("A·B가 서로 다른 인스턴스", base.ip(a.upstream) != base.ip(b.upstream))

marker = f"trace-{uuid.uuid4().hex[:8]}"
st, ev = base.call("POST", f"/sessions/{sid}/events",
                   {"type": "MESSAGE", "clientEventId": str(uuid.uuid4()), "payload": {"content": marker}}, A)
f = b.expect(lambda f: on_sub("s1")(f) and f.json().get("payload", {}).get("content") == marker, 5)
base.check("REST로 보낸 메시지를 다른 인스턴스의 B가 실시간으로 받음", st == 201 and f is not None, (st, f))

# Tempo는 5초 배치로 받으므로 잠시 기다린 뒤 "realtime deliver" 스팬이 있는 트레이스를 찾는다.
trace = None
for _ in range(20):
    time.sleep(3)
    found = grafana("/api/datasources/proxy/uid/tempo/api/search",
                    {"q": '{ name = "realtime deliver" }', "limit": 20, "start": int(time.time()) - 300,
                     "end": int(time.time()) + 60})
    for t in found.get("traces", []):
        full = grafana(f"/api/datasources/proxy/uid/tempo/api/traces/{t['traceID']}")
        spans = []
        for batch in full.get("batches", full.get("resourceSpans", [])):
            attrs = {a["key"]: list(a["value"].values())[0] for a in batch["resource"]["attributes"]}
            for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", [])):
                for s in scope["spans"]:
                    spans.append((attrs.get("service.instance.id"), s["name"]))
        if any("events" in n.lower() and "post" in n.lower() for _, n in spans):
            trace = (t["traceID"], spans)
            break
    if trace:
        break

if trace:
    trace_id, spans = trace
    instances = {i for i, _ in spans}
    names = sorted({n for _, n in spans})
    base.log(f"trace {trace_id}: 인스턴스 {sorted(instances)}")
    base.log("스팬: " + ", ".join(names))
    deliver_nodes = {i for i, n in spans if n == "realtime deliver"}
    http_nodes = {i for i, n in spans if "post" in n.lower()}
    base.check("REST(http post) 스팬", bool(http_nodes))
    base.check("DB(JDBC) 스팬", any(n.startswith("jdbc") or "query" in n.lower() for n in names), names)
    base.check("Redis(PUBLISH) 스팬", any("publish" in n.lower() for n in names), names)
    base.check("받는 노드의 realtime deliver 스팬이 같은 트레이스(다른 인스턴스 포함)",
               bool(deliver_nodes) and len(deliver_nodes | http_nodes) >= 2, (deliver_nodes, http_nodes))
    print(f"\nGrafana: {GRAFANA}/explore (Tempo, trace {trace_id})")
else:
    base.check("Tempo에서 REST → 수신 노드 트레이스를 찾음", False)

passed = sum(1 for _, ok in results if ok)
print(f"\n{passed}/{len(results)} passed")

sys.exit(0 if results and all(ok for _, ok in results) else 1)
