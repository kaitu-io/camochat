#!/usr/bin/env bash
# Fail if tracked files contain internal info listed in scripts/public-scan.deny.
# usage: public-scan.sh [ROOT]   (default: repo root)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${1:-$(cd "$HERE/.." && pwd)}"
DENY="$HERE/public-scan.deny"
LIST="$(mktemp)"; trap 'rm -f "$LIST"' EXIT
git -C "$ROOT" ls-files -z | while IFS= read -r -d '' f; do
  case "$f" in
    scripts/public-scan.deny|scripts/test-public-scan.sh) continue;;
  esac
  [ -f "$ROOT/$f" ] && printf '%s\0' "$f"
done > "$LIST"
hits=0
if [ -n "$(git -C "$ROOT" ls-files | grep -E '(^|/)CLAUD[E]\.md$' | head -n1)" ]; then
  printf 'CLAUD%s.md:tracked\n' E; hits=1
fi
while IFS= read -r pat; do
  [ -z "$pat" ] && continue
  out=$(cd "$ROOT" && xargs -0 grep -HnIE -e "$pat" < "$LIST" 2>/dev/null | P="$pat" awk -F: '{print $1 ":" $2 ":" ENVIRON["P"]}')
  if [ -n "$out" ]; then echo "$out"; hits=1; fi
done < "$DENY"
exit $hits
