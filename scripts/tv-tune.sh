#!/usr/bin/env bash
# Disables the stock Android TV / Google TV home screen and the service that feeds
# its sponsored rows.
#
# Nothing here is destructive: `pm disable-user` only hides a package from the
# current user. `--revert` puts everything back, and so does a factory reset.
#
#   ./scripts/tv-tune.sh            # disable (asks first)
#   ./scripts/tv-tune.sh --revert   # re-enable everything
#   ./scripts/tv-tune.sh --list     # show what is installed and its state
set -euo pipefail

PKG="fr.tvluncher.launcher"

# The stock home apps, and the recommendation engine that supplies their
# sponsored content. Disabling the launchers alone leaves the service running.
PACKAGES=(
  com.google.android.tvlauncher
  com.google.android.apps.tv.launcherx
  com.google.android.tvrecommendations
)

command -v adb >/dev/null || { echo "adb introuvable."; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "Aucun appareil ADB connecté."; exit 1; }

installed() { adb shell pm list packages "$1" 2>/dev/null | grep -q "package:$1"; }
enabled()   { adb shell pm list packages -e "$1" 2>/dev/null | grep -q "package:$1"; }

case "${1:-}" in
  --list)
    for p in "${PACKAGES[@]}"; do
      if ! installed "$p"; then printf '%-45s absent\n' "$p"
      elif enabled "$p";  then printf '%-45s ACTIF\n' "$p"
      else                     printf '%-45s désactivé\n' "$p"; fi
    done
    exit 0
    ;;
  --revert)
    for p in "${PACKAGES[@]}"; do
      installed "$p" || continue
      echo "==> Réactivation de $p"
      adb shell pm enable "$p" >/dev/null 2>&1 || true
    done
    echo "Terminé. Le lanceur d'origine est de nouveau disponible."
    exit 0
    ;;
esac

# The safety check that matters: disabling the only home app on the box leaves it
# with no home screen at all, recoverable only over ADB.
CURRENT_HOME="$(adb shell 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' 2>/dev/null | tail -1 | tr -d '\r')"
if [[ "$CURRENT_HOME" != "$PKG"* ]]; then
  echo "STOP : l'écran d'accueil actuel est « ${CURRENT_HOME:-inconnu} », pas TV Luncher."
  echo
  echo "Désactiver le lanceur d'origine maintenant laisserait la box sans accueil."
  echo "Lancez d'abord ./scripts/install.sh, appuyez sur HOME, choisissez TV Luncher,"
  echo "vérifiez que tout fonctionne, puis relancez ce script."
  exit 1
fi

echo "TV Luncher est bien l'écran d'accueil. Paquets à désactiver :"
for p in "${PACKAGES[@]}"; do installed "$p" && echo "  - $p"; done
echo
read -r -p "Continuer ? [o/N] " answer
[[ "$answer" =~ ^[oOyY]$ ]] || { echo "Annulé."; exit 0; }

for p in "${PACKAGES[@]}"; do
  installed "$p" || continue
  echo "==> Désactivation de $p"
  adb shell pm disable-user --user 0 "$p" >/dev/null 2>&1 \
    || echo "    refusé par la ROM (paquet protégé), on continue"
done

echo
echo "Terminé. Réversible à tout moment avec : ./scripts/tv-tune.sh --revert"
