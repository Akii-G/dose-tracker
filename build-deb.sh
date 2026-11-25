#!/bin/sh
# Builds dose_<version>_all.deb in the current folder.
set -e
cd "$(dirname "$0")"
VERSION=$(sed -n 's/^VERSION = "\(.*\)"/\1/p' src/dose)
PKG=build/dose_${VERSION}_all
rm -rf build && mkdir -p "$PKG/DEBIAN"
install -Dm755 src/dose "$PKG/usr/bin/dose"
install -Dm644 data/app.dose.Dose.desktop "$PKG/usr/share/applications/app.dose.Dose.desktop"
install -Dm644 data/app.dose.Dose.svg "$PKG/usr/share/icons/hicolor/scalable/apps/app.dose.Dose.svg"
install -Dm644 data/dose-background.service "$PKG/usr/lib/systemd/user/dose-background.service"
install -Dm644 data/medicines-it.tsv "$PKG/usr/share/dose/data/medicines-it.tsv"
for po in po/*.po; do install -Dm644 "$po" "$PKG/usr/share/dose/po/$(basename "$po")"; done
install -Dm644 LICENSE "$PKG/usr/share/doc/dose/copyright"
install -Dm644 README.md "$PKG/usr/share/doc/dose/README.md"
SIZE=$(du -sk "$PKG/usr" | cut -f1)
cat > "$PKG/DEBIAN/control" <<CTL
Package: dose
Version: $VERSION
Section: utils
Priority: optional
Architecture: all
Depends: python3 (>= 3.8), python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1, python3-cryptography, libnotify-bin, python3-gi-cairo, python3-cairo, gir1.2-pango-1.0
Installed-Size: $SIZE
Maintainer: Dose <dose@localhost>
Homepage: https://github.com/Akii-G/dose-tracker
Description: Your medicines, quietly
 Keep your medicines and their schedules, get a reminder when a dose is due,
 and see what you took. Syncs with Dose on your iPhone over your home Wi-Fi,
 encrypted. The sister app of Sottovoce.
CTL
cat > "$PKG/DEBIAN/postinst" <<'POST'
#!/bin/sh
# Restart the reminders for whoever is logged in, so an update takes effect at once.
set -e
if [ "$1" = "configure" ] && command -v systemctl >/dev/null; then
  systemctl --global enable dose-background.service >/dev/null 2>&1 || true
fi
exit 0
POST
chmod 755 "$PKG/DEBIAN/postinst"
dpkg-deb --root-owner-group -Zxz --build "$PKG" "dose_${VERSION}_all.deb"
