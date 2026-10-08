#!/usr/bin/env bash
# Tests for public-scan.sh. Run: bash scripts/test-public-scan.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
SCAN="$HERE/public-scan.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" != "$3" ]; then echo "FAIL: $1 (expected $2, got $3)"; fail=1; else echo "ok: $1"; fi; }

git -C "$T" init -q
mkdir -p "$T/scripts"
cp "$HERE/public-scan.deny" "$T/scripts/public-scan.deny"
cp "$SCAN" "$T/scripts/public-scan.sh"
cp "$HERE/test-public-scan.sh" "$T/scripts/test-public-scan.sh"
echo "hello" > "$T/ok.txt"
git -C "$T" add -A
out=$(bash "$SCAN" "$T" 2>&1); check "clean tree (deny file + test excluded)" 0 $?

echo "host cc.52j.me" > "$T/bad.txt"; git -C "$T" add bad.txt
out=$(bash "$SCAN" "$T" 2>&1); rc=$?
check "bad.txt exits 1" 1 $rc
case "$out" in *"bad.txt:1:"*) echo "ok: output names bad.txt:1:";; *) echo "FAIL: output lacks bad.txt:1: ($out)"; fail=1;; esac

git -C "$T" rm -qf --cached bad.txt; rm "$T/bad.txt"
# public tree (no docs/superpowers tracked): CLAUDE.md is NOT excluded
echo "52j.me" > "$T/CLAUDE.md"; git -C "$T" add CLAUDE.md
out=$(bash "$SCAN" "$T" 2>&1); rc=$?
check "public tree: CLAUDE.md hit reported" 1 $rc
case "$out" in *"CLAUDE.md:1:"*) echo "ok: output names CLAUDE.md:1:";; *) echo "FAIL: output lacks CLAUDE.md:1: ($out)"; fail=1;; esac
# private tree (docs/superpowers tracked): CLAUDE.md is excluded
mkdir -p "$T/docs/superpowers"; echo "clean" > "$T/docs/superpowers/clean.md"; git -C "$T" add docs/superpowers/clean.md
out=$(bash "$SCAN" "$T" 2>&1); check "private tree: CLAUDE.md hit excluded" 0 $?
mkdir -p "$T/docs/superpowers" "$T/logs" "$T/spikes"
echo "52j.me" > "$T/docs/superpowers/x.md"
echo "52j.me" > "$T/CLAUDE.md"
echo "52j.me" > "$T/logs/a.txt"
echo "52j.me" > "$T/spikes/a.txt"
git -C "$T" add -A
out=$(bash "$SCAN" "$T" 2>&1); check "excluded prefixes ignored" 0 $?

echo 'storePassword=${SECRET}' > "$T/ok2.txt"; git -C "$T" add ok2.txt
out=$(bash "$SCAN" "$T" 2>&1); check "env-var password reference allowed" 0 $?
echo "storePassword=hunter2" > "$T/ok2.txt"
out=$(bash "$SCAN" "$T" 2>&1); check "literal password flagged" 1 $?

# single-file batch must still print the filename (grep -H)
rm -f "$T/ok2.txt"; git -C "$T" rm -q --cached ok2.txt
mkdir -p "$T/sub/dir"
echo "acct 186180737711" > "$T/sub/dir/acct.txt"; git -C "$T" add sub/dir/acct.txt
out=$(bash "$SCAN" "$T" 2>&1); rc=$?
check "account id in subdirectory flagged" 1 $rc
case "$out" in *"sub/dir/acct.txt:1:"*) echo "ok: output names sub/dir/acct.txt:1:";; *) echo "FAIL: output lacks sub/dir/acct.txt:1: ($out)"; fail=1;; esac

# a tree with exactly one tracked file: grep must still print the filename (-H)
T2="$(mktemp -d)"; trap 'rm -rf "$T" "$T2"' EXIT
git -C "$T2" init -q
mkdir -p "$T2/a/b"
echo "acct 186180737711" > "$T2/a/b/only.txt"; git -C "$T2" add -A
out=$(bash "$SCAN" "$T2" 2>&1); rc=$?
check "single-file tree flagged" 1 $rc
case "$out" in "a/b/only.txt:1:186180737711") echo "ok: single-file output names a/b/only.txt:1:";; *) echo "FAIL: single-file output wrong ($out)"; fail=1;; esac

exit $fail
