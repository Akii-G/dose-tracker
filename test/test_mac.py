"""Mac only (run by .github/workflows/macos.yml): the folders are in Application Support (where
Sottovoce looks for Dose), the background part is a launchd agent with the phone sync, and a
notification can be shown."""
import os, sys, tempfile, time, plistlib, socket, subprocess, importlib.machinery, importlib.util
if sys.platform != "darwin":
    print("mac: skipped (not a Mac)")
    sys.exit(0)
data, cfgdir = tempfile.mkdtemp(), tempfile.mkdtemp()
here = os.path.dirname(os.path.abspath(__file__))
sys.argv = [os.path.join(here, "..", "src", "dose")]
loader = importlib.machinery.SourceFileLoader("dose", sys.argv[0])
spec = importlib.util.spec_from_loader("dose", loader); m = importlib.util.module_from_spec(spec); loader.exec_module(m)

name = m.local_host_name()
print("name on the Wi-Fi:", name + ".local")
assert name and "." not in name, name

def where_stuck(label):
    """What a stuck launchd agent is doing: macOS's `sample` of its process."""
    import re as _re
    out = subprocess.run(["launchctl", "print", "gui/%d/%s" % (os.getuid(), label)], capture_output=True, text=True).stdout
    pid = _re.search(r"\bpid = (\d+)", out)
    if not pid:
        return "not running:\n" + out[-1500:]
    s = subprocess.run(["sample", pid.group(1), "2"], capture_output=True, text=True).stdout
    lines = [l for l in s.splitlines() if "Thread_" in l or "+" in l][:120]
    return "\n".join(lines)


assert m.IS_MAC and m.prefs()["style"] == "tahoe"
assert m.data_dir().endswith("Library/Application Support/Dose"), m.data_dir()
assert m.config_dir().endswith("Library/Application Support/Dose/settings")
print("system language:", m.system_language())

os.environ.update(DOSE_DATA_DIR=data, DOSE_CONFIG_DIR=cfgdir, DOSE_LANG="en")
os.makedirs(cfgdir, exist_ok=True)
m.save_config({"sync": True})
ok, err = m.mac_background_on()
assert ok, err
agent = plistlib.load(open(m.mac_agent_path(), "rb"))
assert agent["ProgramArguments"][-1] == "--background" and agent["KeepAlive"]
assert agent["EnvironmentVariables"]["DOSE_DATA_DIR"] == data
for _ in range(60):
    try:
        socket.create_connection(("127.0.0.1", m.SYNC_PORT), timeout=0.5).close()
        break
    except OSError:
        time.sleep(0.5)
else:
    log = os.path.join(cfgdir, "background.log")
    print(open(log).read()[-4000:] if os.path.exists(log) else "no log")
    print(where_stuck(m.MAC_LABEL))
    raise SystemExit("the background part isn't listening")
assert m.mac_background_running() and m.background_running()
print("background part with sync on %d: OK" % m.SYNC_PORT)
m.mac_background_off()
assert not os.path.exists(m.mac_agent_path()) and not m.mac_background_running()

print("notification:", m.mac_notify("Dose test", "A test notification"))
print("mac ok")
