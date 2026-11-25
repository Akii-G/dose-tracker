"""The Windows parts that don't need Windows: the reminder's dose: links and the toast's XML.
On Windows (the workflow) it also checks the data folder Sottovoce looks in."""
import datetime as dt, importlib.machinery, importlib.util, json, os, tempfile, time
import xml.dom.minidom
os.environ.update(DOSE_DATA_DIR=tempfile.mkdtemp(), DOSE_CONFIG_DIR=tempfile.mkdtemp(), DOSE_LANG="en")
here = os.path.dirname(os.path.abspath(__file__))
loader = importlib.machinery.SourceFileLoader("dose", os.path.join(here, "..", "src", "dose"))
spec = importlib.util.spec_from_loader("dose", loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)

day = dt.date.today()
s = m.Store()
s.put_medicine({"id": "a", "name": "A", "schedule": {"kind": "daily", "times": ["08:00"], "start": "2026-01-01"}})
s.put_medicine({"id": "b", "name": "B", "schedule": {"kind": "daily", "times": ["08:00", "20:00"], "start": "2026-01-01"}})
s.save()

# Taken marks every unmarked dose of that time, and nothing else
assert m.handle_link("dose:taken?day=%s&time=08:00" % day.isoformat())
s = m.Store()
assert sorted(d["med"] for d in s.doses.values() if d["status"] == "taken") == ["a", "b"]
assert all(d["time"] == "08:00" for d in s.doses.values())

# Later writes a snooze the background part picks up
before = time.time()
assert m.handle_link("dose:later?day=%s&time=20:00" % day.isoformat())
with open(m.notified_path(), encoding="utf-8") as f:
    seen = json.load(f)
assert seen["%s 20:00" % day.isoformat()]["until"] >= before + 9 * 60
when = dt.datetime.combine(day, dt.time(20, 1))
seen["%s 20:00" % day.isoformat()]["until"] = when.timestamp() + 9 * 60   # as if Later was pressed at 20:00
assert m.due_now(m.Store(), when, True, seen) == []                    # not before 10 minutes
seen["%s 20:00" % day.isoformat()]["until"] = when.timestamp() - 1
assert [t for _k, t, _m, again in m.due_now(m.Store(), when, True, seen)] == ["20:00"]

# anything else opens the window; bad values are refused
assert not m.handle_link("dose:open")
assert not m.handle_link("dose:taken?day=nope&time=08:00")
assert not m.handle_link("dose:taken?day=%s&time=25:00" % day.isoformat())

# the toast is valid XML, with the two buttons carrying the dose: links
x = m.toast_xml("08:00 · time for <your> medicine", "A & B", day.isoformat(), "08:00",
                [("taken", "Taken"), ("later", "Later")])
doc = xml.dom.minidom.parseString(x)
args = [a.getAttribute("arguments") for a in doc.getElementsByTagName("action")]
assert args == ["dose:taken?day=%s&time=08:00" % day.isoformat(), "dose:later?day=%s&time=08:00" % day.isoformat()]
assert doc.documentElement.getAttribute("scenario") == "reminder"
assert not xml.dom.minidom.parseString(m.toast_xml("t", "b")).getElementsByTagName("action")

if m.IS_WINDOWS:
    del os.environ["DOSE_DATA_DIR"]
    assert m.data_dir().lower().endswith(os.path.join("appdata", "local", "dose")), m.data_dir()
print("windows ok")
