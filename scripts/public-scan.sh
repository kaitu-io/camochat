#!/usr/bin/env bash
# Fail if tracked files contain internal info listed in scripts/public-scan.deny.
# usage: public-scan.sh [ROOT]   (default: repo root)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="${1:-$(cd "$HERE/.." && pwd)}"
DENY="$HERE/public-scan.deny"
LIST="$(mktemp)"; trap 'rm -f "$LIST"' EXIT
# The private paths (docs/s[u]perpowers/, CLAUDE.md, logs/, spikes/) are stripped
# before publishing, so they are excluded only while the scanned repo still
# tracks docs/s[u]perpowers/ (i.e. the private repo). In the public tree they
# should not exist; if they do, scan them like anything else.
PRIVATE=0
[ -n "$(git -C "$ROOT" ls-files -- 'docs/super''powers/' | head -n1)" ] && PRIVATE=1
git -C "$ROOT" ls-files -z | while IFS= read -r -d '' f; do
  case "$f" in
    scripts/public-scan.deny|scripts/test-public-scan.sh) continue;;
  esac
  if [ "$PRIVATE" = 1 ]; then
    case "$f" in
      docs/s[u]perpowers/*|CLAUDE.md|logs/*|spikes/*) continue;;
    esac
  fi
  [ -f "$ROOT/$f" ] && printf '%s\0' "$f"
done > "$LIST"
hits=0
while IFS= read -r pat; do
  [ -z "$pat" ] && continue
  out=$(cd "$ROOT" && xargs -0 grep -HnIE -e "$pat" < "$LIST" 2>/dev/null | awk -F: -v p="$pat" '{print $1 ":" $2 ":" p}')
  if [ -n "$out" ]; then echo "$out"; hits=1; fi
done < "$DENY"
exit $hits
