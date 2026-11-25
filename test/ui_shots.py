"""Opens the window with sample medicines and saves a picture of each page (DOSE_SHOTS=dir)."""
import datetime as dt, importlib.machinery, importlib.util, os, sys, tempfile
os.environ.update(DOSE_DATA_DIR=tempfile.mkdtemp(), DOSE_CONFIG_DIR=tempfile.mkdtemp())
here = os.path.dirname(os.path.abspath(__file__))
loader = importlib.machinery.SourceFileLoader("dose", os.path.join(here, "..", "src", "dose"))
spec = importlib.util.spec_from_loader("dose", loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)
from gi.repository import GLib, Gtk, Graphene
out = os.environ.get("DOSE_SHOTS", tempfile.mkdtemp())
t = m.today()
s = m.Store()
start = (t - dt.timedelta(days=40)).isoformat()
s.put_medicine({"id": "a", "name": "Sertralina Accord", "strength": "50 mg", "form": "Compressa rivestita con film",
                "amount": 1, "unit": "pill", "schedule": {"kind": "daily", "times": ["08:00"], "start": start},
                "refillDays": 7}, when=t - dt.timedelta(days=40))
s.put_medicine({"id": "b", "name": "Vitamina D", "amount": 10, "unit": "drop", "note": "with breakfast",
                "schedule": {"kind": "daily", "times": ["09:00"], "start": start}})
s.put_medicine({"id": "c", "name": "Melatonina", "strength": "1 mg", "amount": 1, "unit": "pill",
                "schedule": {"kind": "daily", "times": ["22:30"], "start": start}})
s.put_medicine({"id": "d", "name": "Tachipirina", "strength": "500 mg", "amount": 1, "unit": "pill",
                "schedule": {"kind": "needed"}})
import random; random.seed(3)
for i in range(1, 40):
    day = t - dt.timedelta(days=i)
    for tm, mid in (("08:00", "a"), ("09:00", "b"), ("22:30", "c")):
        r = random.random()
        if r < 0.85: s.mark(mid, day, tm, "taken", at=tm)
        elif r < 0.92: s.mark(mid, day, tm, "skipped")
s.mark("a", t, "08:00", "taken", at="08:05")
s.take_now("d", day=t, at="11:20")
s.put_medicine(dict(s.meds["a"], amount=1.5), when=t - dt.timedelta(days=12))
s.put_medicine(dict(s.meds["a"], stock={"count": 24, "set": m.now_stamp()}))
s.save()
m.set_pref("style", os.environ.get("DOSE_STYLE", "default"))
app = m._make_app_class()(); err = []

def snap(w, name):
    p = Gtk.WidgetPaintable.new(w); W, H = w.get_width(), w.get_height()
    sn = Gtk.Snapshot(); p.snapshot(sn, W, H)
    tex = w.get_native().get_renderer().render_texture(sn.to_node(), Graphene.Rect().init(0, 0, W, H))
    tex.save_to_png(os.path.join(out, name + ".png"))

def step(i=[0]):
    try:
        win = app.get_active_window()
        k = i[0]; i[0] += 1
        if k == 0: snap(win, "today"); win.stack.set_visible_child_name("medicines")
        elif k == 1: snap(win, "medicines"); win.stack.set_visible_child_name("history")
        elif k == 2: snap(win, "history"); win.edit("a")
        elif k == 3:
            ed = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.Editor)][0]; snap(ed, "editor"); ed.close(); win.edit(None)
        elif k == 4:
            ed = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.Editor)][0]
            ed.search.set_text("sertral")
        elif k == 5:
            ed = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.Editor)][0]
            snap(ed, "search"); ed.close(); m.SyncWindow(win).present()
        elif k == 6:
            sw = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.SyncWindow)][0]; snap(sw, "sync")
            sw.close(); win.edit("a")
        elif k == 7:   # a different amount at each time
            ed = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.Editor)][0]
            ed.add_time("20:00"); ed.per_time.set_active(True); ed.time_amounts[-1].set_value(0.5)
        elif k == 8:
            ed = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.Editor)][0]; snap(ed, "per-time")
            assert ed.collect()["schedule"]["amounts"] == {"08:00": 1.5, "20:00": 0.5}, ed.collect()["schedule"]
            ed.close(); m.ReportWindow(win).present()
        elif k == 9:
            rw = [w for w in Gtk.Window.list_toplevels() if isinstance(w, m.ReportWindow)][0]; snap(rw, "report")
            app.quit(); return False
    except Exception as e:
        import traceback; traceback.print_exc(); err.append(e); app.quit(); return False
    return True

GLib.timeout_add(1500, lambda: GLib.timeout_add(700, step) and False)
app.run([])
print("FAIL" if err else "ui ok", out)
sys.exit(1 if err else 0)
