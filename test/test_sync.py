"""The sync server: a phone's encrypted request is merged and the whole state comes back."""
import datetime as dt, importlib.machinery, importlib.util, json, os, tempfile, threading, urllib.request
os.environ.update(DOSE_DATA_DIR=tempfile.mkdtemp(), DOSE_CONFIG_DIR=tempfile.mkdtemp(), DOSE_LANG="en")
here = os.path.dirname(os.path.abspath(__file__))
loader = importlib.machinery.SourceFileLoader("dose", os.path.join(here, "..", "src", "dose"))
spec = importlib.util.spec_from_loader("dose", loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)

pc = m.Store()
pc.put_medicine({"id": "a", "name": "A", "schedule": {"kind": "daily", "times": ["08:00"], "start": "2026-11-01"}})
pc.save()
phone = m.Store("")
phone.put_medicine({"id": "b", "name": "B", "schedule": {"kind": "needed"}})
phone.take_now("b", day=dt.date(2026, 11, 25), at="10:00")
token = m.sync_config()["token"]
server = m.make_server(0)
port = server.server_address[1]
threading.Thread(target=server.serve_forever, daemon=True).start()

def post(path, doc, tok=token):
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (port, path), data=m.seal(tok, path, doc), method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, m.unseal(tok, path, r.read(), reply=True)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

assert post("/api/e/ping", {})[0] == 403                     # switched off
m.set_pref("sync", True)
code, doc = post("/api/e/ping", {})
assert code == 200 and doc["app"] == "Dose"
assert post("/api/e/ping", {}, tok="wrong-token-0123456789")[0] == 401
code, state = post("/api/e/sync", dict(phone.to_json(), device="iPhone"))
assert code == 200 and state["changed"] == 2, state
assert {x["id"] for x in state["medicines"]} == {"a", "b"} and len(state["doses"]) == 1
assert set(m.Store().meds) == {"a", "b"}                     # saved on the PC
phone.absorb(state)
assert set(phone.meds) == {"a", "b"}
server.shutdown()
print("sync ok")
