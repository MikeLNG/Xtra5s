#!/usr/bin/env bash
# Xtra5s: checks that every place a custom feature plugs into official Xtra code is still there.
# A hook is a line in an official file that is marked "Xtra5 custom" / "Xtra5s custom" or calls
# into the custom package. .github/xtra5s-hooks.txt lists how many each file had; after merging
# official Xtra, a file with fewer (or gone) means a feature may have been disconnected, so the
# release workflow stops before building or publishing anything.
#   .github/xtra5s-hooks.sh           check (exit 1 if a hook is missing)
#   .github/xtra5s-hooks.sh --update  rewrite the list (after adding or removing hooks on purpose)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST=.github/xtra5s-hooks.txt
PATTERN='Xtra5s? custom|xtra\.custom\.[A-Za-z]'

count_all() {
  grep -rlE "$PATTERN" app/src/main app/build.gradle.kts 2>/dev/null \
    | grep -v '/xtra/custom/' | grep -v '/res/drawable/xtra5' | grep -v '/res/values/xtra5' \
    | sort | while read -r f; do echo "$(grep -cE "$PATTERN" "$f") $f"; done
}

if [ "${1:-}" = "--update" ]; then
  count_all > "$LIST"
  echo "Wrote $(wc -l < "$LIST") files to $LIST"
  exit 0
fi

missing=0
while read -r expected file; do
  [ -z "${file:-}" ] && continue
  if [ ! -f "$file" ]; then
    echo "::error::$file is gone (it had $expected Xtra5s hook(s))"
    missing=1
    continue
  fi
  actual=$(grep -cE "$PATTERN" "$file" || true)
  if [ "$actual" -lt "$expected" ]; then
    echo "::error::$file has $actual Xtra5s hook(s), expected $expected"
    missing=1
  fi
done < "$LIST"
if [ "$missing" -ne 0 ]; then
  echo "::error::Some Xtra5s hooks are missing after merging official Xtra: a custom feature may be disconnected. Nothing was published."
  exit 1
fi
echo "All Xtra5s hooks present ($(wc -l < "$LIST") files)"
