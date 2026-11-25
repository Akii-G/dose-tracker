#!/usr/bin/env python3
"""Every translatable text of the PC app; --check lists those missing from po/it.po.

Usage: tools/strings.py [--check]"""
import ast, importlib.machinery, importlib.util, json, os, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "src", "dose")


def extract():
    out = []
    for node in ast.walk(ast.parse(open(SRC, encoding="utf-8").read())):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            a = node.args
            if node.func.id == "tr" and a and isinstance(a[0], ast.Constant) and isinstance(a[0].value, str):
                key = a[0].value
            elif node.func.id == "trn" and len(a) >= 2 and all(isinstance(x, ast.Constant) for x in a[:2]):
                key = (a[0].value, a[1].value)
            else:
                continue
            if key not in [k for k, _ in out]:
                out.append((key, node.lineno))
    return [k for k, _ in sorted(out, key=lambda e: e[1])]


if __name__ == "__main__":
    keys = extract()
    if "--check" in sys.argv:
        loader = importlib.machinery.SourceFileLoader("dose_po", SRC)
        spec = importlib.util.spec_from_loader("dose_po", loader)
        mod = importlib.util.module_from_spec(spec); loader.exec_module(mod)
        cat = mod.read_po(os.path.join(ROOT, "po", "it.po"))
        missing = [k for k in keys if k not in cat]
        for k in missing:
            print("missing:", json.dumps(k, ensure_ascii=False))
        print("%d texts, %d missing" % (len(keys), len(missing)))
        sys.exit(1 if missing else 0)
    for k in keys:
        print(json.dumps(k, ensure_ascii=False))
