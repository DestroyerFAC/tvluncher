#!/usr/bin/env bash
# Installs TV Luncher on an Android TV box over ADB and hands it the home role.
#
#   ./scripts/install.sh                     # device already connected via USB/ADB
#   ./scripts/install.sh 192.168.1.42        # connect over the network first
#   APK=path/to.apk ./scripts/install.sh     # install a specific APK
set -euo pipefail

PKG="fr.tvluncher.launcher"
ACTIVITY="${PKG}/${PKG}.ui.HomeActivity"
TARGET_IP="${1:-}"

command -v adb >/dev/null || { echo "adb introuvable. Installez les platform-tools Android."; exit 1; }

if [[ -n "$TARGET_IP" ]]; then
  echo "==> Connexion à ${TARGET_IP}:5555"
  adb connect "${TARGET_IP}:5555" >/dev/null
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "Aucun appareil ADB. Activez le débogage sur la box, puis relancez."
  exit 1
fi

APK="${APK:-}"
if [[ -z "$APK" ]]; then
  # Prefer the release build; fall back to whatever the last Gradle run produced.
  for candidate in \
    app/build/outputs/apk/release/app-release.apk \
    app/build/outputs/apk/debug/app-debug.apk; do
    [[ -f "$candidate" ]] && APK="$candidate" && break
  done
fi
[[ -f "$APK" ]] || { echo "APK introuvable. Lancez ./gradlew assembleRelease ou définissez APK=..."; exit 1; }

echo "==> Installation de $APK"
adb install -r -g "$APK"

echo "==> Attribution du rôle « écran d'accueil »"
# Works on most Android TV builds. When it does not, the fallback below makes the
# system ask the user, which always works.
if ! adb shell cmd package set-home-activity "$ACTIVITY" 2>/dev/null | grep -qi success; then
  echo "    set-home-activity indisponible sur cette ROM."
  echo "    Effacement du lanceur par défaut : appuyez sur HOME et choisissez TV Luncher."
  adb shell pm clear-package-preferred-activities com.google.android.tvlauncher 2>/dev/null || true
  adb shell pm clear-package-preferred-activities com.google.android.apps.tv.launcherx 2>/dev/null || true
fi

CURRENT="$(adb shell cmd shortcut get-default-launcher 2>/dev/null || true)"
echo "==> Lanceur courant : ${CURRENT:-inconnu}"
echo
echo "Terminé. Appuyez sur HOME sur la télécommande."
echo "Pour supprimer les bandeaux sponsorisés du lanceur d'origine :"
echo "    ./scripts/tv-tune.sh"
