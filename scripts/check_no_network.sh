#!/usr/bin/env bash
# Fait échouer le build si l'APK (ou le manifeste fusionné) demande un accès réseau.
set -euo pipefail
APK="$1"
FORBIDDEN='android.permission.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE)'
status=0

echo "== Manifestes fusionnés =="
found=0
while IFS= read -r m; do
  found=1
  echo "-> $m"
  if grep -E "uses-permission[^>]*$FORBIDDEN" "$m"; then
    echo "::error file=$m::Permission réseau interdite dans le manifeste fusionné"
    status=1
  fi
done < <(find app/build/intermediates -name AndroidManifest.xml -path '*merged_manifest*' -path '*elease*' 2>/dev/null)
[ "$found" = 1 ] || { echo "::error::Manifeste fusionné introuvable"; status=1; }

echo "== Permissions de l'APK final (aapt2) =="
AAPT2="$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)"
"$AAPT2" dump permissions "$APK" | tee /tmp/perms.txt
if grep -E "$FORBIDDEN" /tmp/perms.txt; then
  echo "::error::L'APK demande une permission réseau : build refusé."
  status=1
fi
[ "$status" = 0 ] && echo "✅ Aucune permission réseau : l'application est 100 % hors ligne."
exit $status
