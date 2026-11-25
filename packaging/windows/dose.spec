# PyInstaller recipe for Dose on Windows (run from the repository root, in MSYS2 UCRT64, after
# copying src/dose to build/dose.py). Makes dist/Dose/ with Dose.exe.
import os
import shutil

root = os.path.abspath(os.path.join(SPECPATH, "..", ".."))
datas = [(os.path.join(root, "po", "*.po"), "po"),
         (os.path.join(root, "data", "medicines-it.tsv"), "data"),
         (os.path.join(root, "data", "app.dose.Dose.svg"), "share/icons/hicolor/scalable/apps"),
         # a PNG too: the bundled GTK may have no SVG loader. Found directly in share/icons (unthemed)
         (os.path.join(SPECPATH, "app.dose.Dose.png"), "share/icons"),
         (os.path.join(SPECPATH, "app.dose.Dose.png"), "share/icons/hicolor/256x256/apps")]

# GLib starts gdbus.exe for GApplication on Windows; without it GLib warns and may give up
gdbus = shutil.which("gdbus.exe") or shutil.which("gdbus")
binaries = [(gdbus, ".")] if gdbus else []

a = Analysis(
    [os.path.join(root, "build", "dose.py")],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=["http.server", "cryptography.hazmat.primitives.ciphers.aead",
                   "cryptography.hazmat.primitives.kdf.hkdf", "winreg", "csv",
                   "cairo", "gi.repository.Pango", "gi.repository.PangoCairo"],     # the report PDF
    hookspath=[],
    hooksconfig={
        "gi": {
            "icons": ["Adwaita", "hicolor"],
            "themes": ["Adwaita"],
            "module-versions": {"Gtk": "4.0", "Gdk": "4.0", "Adw": "1", "GdkPixbuf": "2.0", "Pango": "1.0", "PangoCairo": "1.0"},
        },
    },
    excludes=["tkinter", "unittest", "pydoc_data"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="Dose", icon=os.path.join(SPECPATH, "dose.ico"),
          console=False, debug=False, strip=False, upx=False)
# the same app with a console window, for bug reports: errors show in the console
debug = EXE(pyz, a.scripts, [], exclude_binaries=True, name="Dose-console", icon=os.path.join(SPECPATH, "dose.ico"),
            console=True, debug=False, strip=False, upx=False)
coll = COLLECT(exe, debug, a.binaries, a.datas, strip=False, upx=False, name="Dose")
