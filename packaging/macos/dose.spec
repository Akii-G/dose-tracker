# PyInstaller recipe for Dose on a Mac (run from the repository root with Homebrew's Python,
# after copying src/dose to build/dose.py and making build/Dose.icns). Makes dist/Dose.app.
import os, re

root = os.path.abspath(os.path.join(SPECPATH, "..", ".."))
version = re.search(r'^VERSION = "(.*)"', open(os.path.join(root, "src", "dose")).read(), re.M).group(1)
datas = [(os.path.join(root, "po", "*.po"), "po"),
         (os.path.join(root, "data", "medicines-it.tsv"), "data"),
         (os.path.join(root, "data", "app.dose.Dose.svg"), "share/icons/hicolor/scalable/apps"),
         (os.path.join(root, "data", "icons", "hicolor", "scalable", "actions", "*.svg"), "share/icons/hicolor/scalable/actions")]

a = Analysis(
    [os.path.join(root, "build", "dose.py")],
    pathex=[],
    binaries=[],
    datas=datas,
    hiddenimports=["http.server", "plistlib", "cryptography.hazmat.primitives.ciphers.aead",
                   "cryptography.hazmat.primitives.kdf.hkdf", "csv",
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
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name="Dose", console=False, debug=False,
          strip=False, upx=False, argv_emulation=False)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="Dose")
app = BUNDLE(
    coll,
    name="Dose.app",
    icon=os.path.join(root, "build", "Dose.icns"),
    bundle_identifier="app.dose.Dose",
    version=version,
    info_plist={
        "CFBundleDisplayName": "Dose",
        "CFBundleShortVersionString": version,
        "CFBundleVersion": version,
        "NSHighResolutionCapable": True,
        "LSApplicationCategoryType": "public.app-category.medical",
        "LSMinimumSystemVersion": "13.0",
        "NSHumanReadableCopyright": "MIT License",
        "NSLocalNetworkUsageDescription": "Dose syncs your medicines with Dose on your phone over your home Wi-Fi.",
        "NSAppleEventsUsageDescription": "Dose shows its reminders with Taken and Later buttons.",
        "CFBundleDevelopmentRegion": "en",
        "CFBundleLocalizations": ["en", "it"],
    },
)
