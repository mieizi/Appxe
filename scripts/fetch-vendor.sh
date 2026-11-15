#!/usr/bin/env bash
# يحمّل مكتبات الواجهة والخطوط إلى داخل التطبيق حتى يعمل بدون إنترنت.
set -euo pipefail
cd "$(dirname "$0")/.."
A=app/src/main/assets
mkdir -p "$A/vendor" "$A/fonts"

fetch() { # fetch <out> <url> [fallback-url]
  local out="$1"; shift
  for url in "$@"; do
    if curl -fsSL --retry 3 -m 60 -o "$out" "$url" && [ -s "$out" ]; then
      echo "ok   $out"; return 0
    fi
  done
  echo "FAIL $out"; return 1
}

# مطلوبان: بدونهما لا تظهر الواجهة
fetch "$A/vendor/tailwind.js" "https://cdn.tailwindcss.com/3.4.16" "https://cdn.tailwindcss.com"
fetch "$A/vendor/lucide.js" \
  "https://unpkg.com/lucide@0.460.0/dist/umd/lucide.min.js" \
  "https://cdn.jsdelivr.net/npm/lucide@0.460.0/dist/umd/lucide.min.js"

# الخط اختياري: عند الفشل يُستخدم خط النظام
FONT="https://cdn.jsdelivr.net/npm/@fontsource/ibm-plex-sans-arabic@5/files"
for subset in arabic latin; do
  for w in 200 300 400 500 600 700; do
    fetch "$A/fonts/plex-$subset-$w.woff2" "$FONT/ibm-plex-sans-arabic-$subset-$w-normal.woff2" \
      || echo "warning: font $subset-$w missing, system font will be used"
  done
done
ls -la "$A/vendor" "$A/fonts"
