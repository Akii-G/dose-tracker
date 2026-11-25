"""Schedules, doses, stock, history, merging two copies, and the medicine search."""
import datetime as dt, importlib.machinery, importlib.util, os, tempfile
os.environ.update(DOSE_DATA_DIR=tempfile.mkdtemp(), DOSE_CONFIG_DIR=tempfile.mkdtemp(), DOSE_LANG="en")
here = os.path.dirname(os.path.abspath(__file__))
loader = importlib.machinery.SourceFileLoader("dose", os.path.join(here, "..", "src", "dose"))
spec = importlib.util.spec_from_loader("dose", loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)

mon = dt.date(2026, 11, 23)
s = m.Store()
a = s.put_medicine({"id": "a", "name": "Sertralina", "strength": "50 mg", "amount": 1, "unit": "pill",
                    "schedule": {"kind": "daily", "times": ["20:00", "08:00"], "start": "2026-11-01"}}, when=mon)
b = s.put_medicine({"id": "b", "name": "Vitamin D", "amount": 10, "unit": "drop",
                    "schedule": {"kind": "weekdays", "times": ["09:00"], "weekdays": [1, 4], "start": "2026-11-01"}})
c = s.put_medicine({"id": "c", "name": "Ibuprofen", "amount": 0.5, "unit": "pill", "schedule": {"kind": "needed"}})
e = s.put_medicine({"id": "e", "name": "Antibiotic", "schedule": {"kind": "every", "every": 2, "times": ["12:00"],
                    "start": "2026-11-23", "end": "2026-11-27"}})
assert a["schedule"]["times"] == ["08:00", "20:00"]
assert [(t, x["id"]) for t, x, _d in s.slots(mon)] == [("08:00", "a"), ("09:00", "b"), ("12:00", "e"), ("20:00", "a")]
tue = mon + dt.timedelta(days=1)
assert [x["id"] for _t, x, _d in s.slots(tue)] == ["a", "a"]                  # no D on Tuesday, antibiotic every 2 days
assert [x["id"] for _t, x, _d in s.slots(mon + dt.timedelta(days=6))] == ["a", "a"]   # course over
assert m.schedule_text(a) == "Every day at 08:00 and 20:00"
assert m.schedule_text(b) == "Mon, Thu at 09:00"
assert m.schedule_text(c) == "When needed"
assert m.schedule_text(e) == "Every 2 days at 12:00 · until 27 Nov 2026"
assert m.fmt_amount(0.5, "pill") == "½ pill" and m.fmt_amount(1.5, "pill") == "1½ pills" and m.fmt_amount(2.5, "ml") == "2.5 ml"
assert m.fmt_amount(10, "drop") == "10 drops"

# doses
s.mark("a", mon, "08:00", "taken", at="08:10")
s.mark("b", mon, "09:00", "skipped")
assert s.day_counts(mon) == (4, 1, 1)
k = s.take_now("c", day=mon, at="15:00")
assert [x["id"] for x in s.extra(mon)] == [k]
s.unmark(m.slot_id("b", mon, "09:00"))
assert s.day_counts(mon) == (4, 1, 0) and m.slot_id("b", mon, "09:00") in s.deleted

# stock: counted from when it was set
a2 = dict(s.meds["a"], stock={"count": 30, "set": "2000-01-01T00:00:00.000Z"}, refillDays=20)
s.put_medicine(a2)
assert s.remaining(s.meds["a"]) == 29 and s.days_left(s.meds["a"]) == 14 and s.low(s.meds["a"])

# history: a new dose is a change, stopping and restarting are kept
s.put_medicine(dict(s.meds["a"], amount=2), when=tue)
assert [h["kind"] for h in s.meds["a"]["history"]] == ["started", "changed"]
s.put_medicine(dict(s.meds["a"], note="with food"))
assert len(s.meds["a"]["history"]) == 2                                      # a note isn't a change of dose
s.set_archived("e", True)
assert s.meds["e"]["history"][-1]["kind"] == "stopped" and not s.slots(tue + dt.timedelta(days=1))[2:]

# save, load, merge
s.save()
t = m.Store()
assert t.to_json() == s.to_json()
phone = m.Store("")
phone.absorb(s.to_json())
phone.mark("a", tue, "08:00", "taken")
phone.delete_medicine("c")
assert s.absorb(phone.to_json()) >= 3
assert "c" not in s.meds and k not in s.doses and s.doses[m.slot_id("a", tue, "08:00")]["status"] == "taken"
assert s.absorb(phone.to_json()) == 0                                         # nothing new the second time
old = dict(phone.meds["a"], name="Old name", updated="2000-01-01T00:00:00.000Z")
assert s.absorb({"medicines": [old]}) == 0 and s.meds["a"]["name"] == "Sertralina"   # older change loses

# junk from a phone is cleaned or dropped
s2 = m.Store("")
s2.read({"medicines": [{"id": "x"}, {"id": "y", "name": "Y", "unit": "?", "amount": "lots",
                                     "schedule": {"kind": "weird", "times": ["25:00", "07:30"]}}],
         "doses": [{"id": "d", "med": "y", "day": "nope", "status": "taken"}]})
assert list(s2.meds) == ["y"] and s2.meds["y"]["unit"] == "pill" and s2.meds["y"]["amount"] == 1
assert s2.meds["y"]["schedule"]["kind"] == "daily" and s2.meds["y"]["schedule"]["times"] == ["07:30"]
assert not s2.doses

# reminders: due once, a follow-up after 30 minutes, nothing once taken
r = m.Store("")
r.put_medicine({"id": "r", "name": "R", "schedule": {"kind": "daily", "times": ["08:00"], "start": "2026-01-01"}})
seen = {}
due = m.due_now(r, dt.datetime(2026, 11, 23, 8, 1), True, seen)
assert [(x[1], x[3]) for x in due] == [("08:00", False)]
seen[due[0][0]] = {"until": 0, "again": False}
assert not m.due_now(r, dt.datetime(2026, 11, 23, 8, 20), True, seen)
assert [x[3] for x in m.due_now(r, dt.datetime(2026, 11, 23, 8, 31), True, seen)] == [True]
assert not m.due_now(r, dt.datetime(2026, 11, 23, 8, 31), False, seen)
r.mark("r", dt.date(2026, 11, 23), "08:00", "taken")
assert not m.due_now(r, dt.datetime(2026, 11, 23, 8, 31), True, seen)
assert m.reminder_text("08:00", [r.meds["r"]], True) == ("Dose", "Time for your 08:00 dose")

# search, on the device
found = m.search_medicines("sertral")
assert found and all(x[0].lower().startswith("sertral") for x in found[:5]), found[:3]
assert any(x[3].lower().startswith("escitalopram") for x in m.search_medicines("escitalopram"))
assert m.search_medicines("a") == []

# encryption round trip
if m.crypto_available():
    box = m.seal("token-1234567890abcdef", "/api/e/sync", {"x": 1}, reply=True)
    assert m.unseal("token-1234567890abcdef", "/api/e/sync", box, reply=True) == {"x": 1}
    try:
        m.unseal("another-token-123456789", "/api/e/sync", box, reply=True); raise SystemExit("opened with a wrong token")
    except ValueError:
        pass
print("model ok")

# the same vectors ios/CryptoCheck checks
if m.crypto_available():
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    k = m.sync_key("test-token-0123456789")
    assert k.hex() == "3fbf719d97272440ee91a3f08240c8bae2cb00600c386941d0a7ec35357340ca"
    assert AESGCM(k).encrypt(bytes(12), b'{"a":1}', m.sync_aad("/api/e/ping", False)).hex() == \
        "8795b68f38be54580422caa7482cff3c237c0969349bed"
    print("crypto vectors ok")

# a different amount at each time: kept only for its times, used for doses, stock and the text
s = m.Store(path="")
med = s.put_medicine({"id": "pt", "name": "Per time", "amount": 1, "unit": "pill",
                      "schedule": {"kind": "daily", "times": ["08:00", "20:00"], "start": "2026-01-01",
                                   "amounts": {"20:00": 0.5, "13:00": 2, "08:00": "x"}}})
med = s.meds["pt"]
assert med["schedule"]["amounts"] == {"20:00": 0.5}, med["schedule"]
assert m.amount_at(med, "08:00") == 1 and m.amount_at(med, "20:00") == 0.5
assert m.per_day(med) == 1.5
s.mark("pt", dt.date(2026, 2, 1), "20:00", "taken")
assert s.doses["pt@2026-02-01@20:00"]["amount"] == 0.5
assert "20:00 (½" in m.schedule_text(med), m.schedule_text(med)
assert "amounts" not in m.clean_schedule({"kind": "daily", "times": ["08:00"]})
print("per-time amounts ok")

# the AIC number from the box: typed, Code 39, Code 32 and GS1 DataMatrix
assert m.aic_from_text("A012745093") == "012745093" and m.aic_from_text(" 012745093 ") == "012745093"
assert m.aic_from_text("12345") is None and m.aic_from_text("tachipirina") is None
assert m.aic_from_barcode("A012745093") == "012745093"
assert m.aic_from_barcode("0D4YD5") == "012745093"
assert m.aic_from_barcode("0CSSBD") == "012345676"                     # Code 32, published example
gs = "\x1d"
dm = "]d2" + "0108012345678906" + "21ABC123" + gs + "17271231" + "10L01" + gs + "716012745093"
assert m.aic_from_barcode(dm) == "012745093", m.aic_from_barcode(dm)
assert m.aic_from_barcode("010801234567890617271231716012745093") == "012745093"
assert m.aic_from_barcode("hello") is None
m._MEDICINES = [("Tachipirina", "500 mg", "Compressa", "Paracetamolo", "tachipirina", "paracetamolo", "012745:093,105"),
                ("Tachipirina", "1000 mg", "Compressa", "Paracetamolo", "tachipirina", "paracetamolo", "012745:220")]
assert m.find_aic("012745220")[1] == "1000 mg"
assert m.find_aic("012745999")[1] == "500 mg"                # another pack of the same medicine
assert m.find_aic("999999999") is None
assert [r[1] for r in m.search_medicines("A012745105")] == ["500 mg"]
m._MEDICINES = None
print("aic ok")
