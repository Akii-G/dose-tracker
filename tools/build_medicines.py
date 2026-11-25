#!/usr/bin/env python3
"""Builds data/medicines-it.tsv from AIFA's open data (CC-BY 4.0, https://www.aifa.gov.it/open-data):
one line per medicine name, strength and form, with its active ingredient, for the search when
you add a medicine, and the AIC codes of its packs, to find it from the barcode on the box. Run by CI before each build, so the list stays current; never at run time.

Usage: tools/build_medicines.py [confezioni_fornitura.csv]   (downloads it when not given)"""
import csv, io, os, re, sys, urllib.request

URL = "https://drive.aifa.gov.it/farmaci/confezioni_fornitura.csv"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data", "medicines-it.tsv")
UNIT = r"(?:MG|G|MCG|µG|MICROGRAMMI|UI|U\.I\.|ML|MMOL|%)"
STRENGTH = re.compile(r"(?<![\w.,])(\d+(?:[.,]\d+)?\s*%s(?:\s*/\s*\d*(?:[.,]\d+)?\s*%s)?"
                      r"(?:\s*\+\s*\d+(?:[.,]\d+)?\s*%s(?:\s*/\s*\d*(?:[.,]\d+)?\s*%s)?)*)" % (UNIT, UNIT, UNIT, UNIT), re.I)
SKIP_FORMS = ("granuli", "globuli", "gas medicinale", "non nota", "tisana", "trituraz")


def title(s):
    """TACHIPIRINA -> Tachipirina; keeps short all-caps words like 'AB' or 'OD'."""
    out = []
    for w in s.split():
        out.append(w if len(w) <= 2 and w.isalpha() else w.capitalize() if w.isupper() else w)
    return " ".join(out)


def strength(desc):
    m = STRENGTH.search(desc)
    if not m:
        return ""
    s = re.sub(r"\s+", " ", m.group(1)).strip()
    s = re.sub(r"\s*/\s*", "/", s)
    s = re.sub(r"(\d)\s*(MG|G|MCG|UI|ML|MMOL|%)", r"\1 \2", s, flags=re.I)
    return s.lower().replace(" ui", " UI").replace("mcg", "mcg")


def packs(codes):
    """['012345045', '012345057', '067890011'] -> '012345:045,057;067890:011' (AIC = 6 + 3 digits)."""
    groups = {}
    for c in sorted(codes):
        groups.setdefault(c[:6], []).append(c[6:])
    return ";".join("%s:%s" % (k, ",".join(v)) for k, v in groups.items())


def build(text):
    seen, rows, codes = set(), [], {}
    for r in csv.DictReader(io.StringIO(text), delimiter=";"):
        if r.get("STATO_AMMINISTRATIVO") != "Autorizzata":
            continue
        form = (r.get("FORMA") or "").strip()
        if not form or form.lower().startswith(SKIP_FORMS):
            continue
        name = title((r.get("DENOMINAZIONE") or "").strip())
        if not name:
            continue
        line = (name, strength(r.get("DESCRIZIONE") or ""), form[:1].upper() + form[1:].lower(),
                (r.get("PA_ASSOCIATI") or "").strip().capitalize())
        if line not in seen:
            seen.add(line)
            rows.append(line)
        aic = re.sub(r"\D", "", r.get("CODICE_AIC") or "")
        if len(aic) == 9:
            codes.setdefault(line, set()).add(aic)
    # a line without a strength adds nothing when the same name and form has one;
    # its packs go to the line that has the strength
    named = {}
    for r in rows:
        if r[1]:
            named.setdefault((r[0], r[2]), r)
    for r in rows:
        if not r[1] and (r[0], r[2]) in named:
            codes.setdefault(named[(r[0], r[2])], set()).update(codes.get(r, ()))
    rows = [r for r in rows if r[1] or (r[0], r[2]) not in named]
    rows.sort(key=lambda x: (x[0].lower(), x[1], x[2]))
    return [r + (packs(codes.get(r, ())),) for r in rows]


def main():
    if len(sys.argv) > 1:
        text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    else:
        with urllib.request.urlopen(URL, timeout=120) as f:
            text = f.read().decode("utf-8", "replace")
    rows = build(text)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Medicines authorised in Italy, from AIFA open data (CC-BY 4.0): name, strength, form, active ingredient, "
                "AIC codes of the packs (6 digits:last 3 digits)\n")
        for row in rows:
            f.write("\t".join(c.replace("\t", " ") for c in row) + "\n")
    print("%d medicines -> %s (%d KB)" % (len(rows), OUT, os.path.getsize(OUT) // 1024))


if __name__ == "__main__":
    main()
