#!/usr/bin/env python3
"""The icons for each kind of medicine (the unit: pill, capsule, drop…), drawn once here.

    python3 tools/make_icons.py

Writes data/icons/hicolor/scalable/actions/dose-unit-<unit>-symbolic.svg (the PC app, recoloured by
GTK) and android/app/src/main/res/drawable/ic_unit_<unit>.xml. The iPhone uses SF Symbols."""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")

# unit: (path in a 24x24 box, rotation around the centre, even-odd fill)
ICONS = {
    "pill": ("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z M3 11.1h18v1.8H3z", 0, True),
    "capsule": ("M12 1.5a4.5 4.5 0 0 1 4.5 4.5v12a4.5 4.5 0 0 1-9 0V6A4.5 4.5 0 0 1 12 1.5z M7.5 11.1h9v1.8h-9z", 45, True),
    "drop": ("M12 2.5C12 2.5 5 10.5 5 15a7 7 0 0 0 14 0c0-4.5-7-12.5-7-12.5z", 0, False),
    "ml": ("M8 2.5h8v2.2h-1.2v4.2l4.4 9.4a2 2 0 0 1-1.8 2.9H6.6a2 2 0 0 1-1.8-2.9l4.4-9.4V4.7H8z", 0, False),
    "puff": ("M8.5 1.5h5v6h-5z M6.5 8.5h9v6.5h3.5a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H8.5a2 2 0 0 1-2-2z", 0, False),
    "sachet": ("M5 6.5 6.75 4.5 8.5 6.5 10.25 4.5 12 6.5 13.75 4.5 15.5 6.5 17.25 4.5 19 6.5V19a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z", 0, False),
    "patch": ("M4 7.5h16a3 3 0 0 1 3 3v3a3 3 0 0 1-3 3H4a3 3 0 0 1-3-3v-3a3 3 0 0 1 3-3z M9 9.25h6v5.5H9z", -45, True),
    "injection": ("M8 1.5h8v1.8h-3v1.7h2v11h-6v-11h2V3.3H8z M6.5 15.5h11v1.8h-11z M11.4 17.3h1.2V23h-1.2z", 45, False),
    "application": ("M5.5 1.5h13v2.5l-1 1V16l-3.5 3v3h-4v-3L6.5 16V5l-1-1z", 0, False),
}


def main():
    svgdir = os.path.join(ROOT, "data", "icons", "hicolor", "scalable", "actions")
    drawable = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable")
    os.makedirs(svgdir, exist_ok=True)
    for unit, (d, rot, evenodd) in ICONS.items():
        rule = ' fill-rule="evenodd"' if evenodd else ""
        with open(os.path.join(svgdir, "dose-unit-%s-symbolic.svg" % unit), "w") as f:
            f.write('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24">'
                    '<g transform="rotate(%d 12 12)"><path fill="#2e3436"%s d="%s"/></g></svg>\n' % (rot, rule, d))
        with open(os.path.join(drawable, "ic_unit_%s.xml" % unit), "w") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- tools/make_icons.py -->\n'
                    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                    '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
                    '    <group android:rotation="%d" android:pivotX="12" android:pivotY="12">\n'
                    '        <path android:fillColor="#FF000000"%s android:pathData="%s" />\n'
                    '    </group>\n</vector>\n' % (rot, ' android:fillType="evenOdd"' if evenodd else "", d))
    print(len(ICONS), "icons")


if __name__ == "__main__":
    main()
