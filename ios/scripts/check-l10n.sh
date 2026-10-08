#!/usr/bin/env bash
# Guard: iOS UI source must not contain Han characters inside string literals.
# UI text comes from design/strings/*.json -> generated L10n.
#
# Run locally from the repo root:   bash ios/scripts/check-l10n.sh
# Verify the guard itself:          bash ios/scripts/check-l10n.sh --self-test
#
# Skipped: comments, lines containing NSLog( / precondition( / assertionFailure(
# / fatalError( or "// l10n-ignore:", ChencangShared/{UAT,L10n,Generated}/,
# and UATThreadStress.swift.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

scan() { # files from stdin (NUL separated); prints file:line: text, exits 1 if any
  xargs -0 perl -CSD -e '
    use strict; use warnings;
    my $bad = 0;
    for my $file (@ARGV) {
      open(my $fh, "<:encoding(UTF-8)", $file) or die "$file: $!";
      local $/; my $src = <$fh>; close $fh;
      my @lines = split(/\n/, $src, -1);
      my %hit;
      my $n = length $src; my $i = 0; my $line = 1;
      # state stack for nested code inside interpolation: each entry = {hashes, depth}
      my @stack;
      my $mode = "code"; my ($multi, $hashes) = (0, 0); my $block = 0; my $depth = 0;
      my @saved;
      while ($i < $n) {
        my $c = substr($src, $i, 1);
        if ($c eq "\n") { $line++; $i++; next; }
        if ($mode eq "code") {
          my $two = substr($src, $i, 2);
          if ($two eq "//") { $i++ while $i < $n && substr($src,$i,1) ne "\n"; next; }
          if ($two eq "/*") { $block = 1; $i += 2; $mode = "block"; next; }
          if ($c eq "#" || $c eq "\"") {
            my $j = $i; my $h = 0;
            while (substr($src,$j,1) eq "#") { $h++; $j++; }
            if (substr($src,$j,1) eq "\"") {
              $hashes = $h;
              if (substr($src,$j,3) eq "\"\"\"") { $multi = 1; $i = $j + 3; }
              else { $multi = 0; $i = $j + 1; }
              $mode = "str"; next;
            }
          }
          if ($c eq "(" ) { $depth++ }
          elsif ($c eq ")") {
            if (@saved && $depth == $saved[-1]{depth}) {
              my $s = pop @saved;
              ($hashes, $multi, $depth) = ($s->{hashes}, $s->{multi}, $s->{outer});
              $mode = "str"; $i++; next;
            }
            $depth--;
          }
          $i++; next;
        }
        if ($mode eq "block") {
          my $two = substr($src, $i, 2);
          if ($two eq "/*") { $block++; $i += 2; next; }
          if ($two eq "*/") { $block--; $i += 2; $mode = "code" if $block == 0; next; }
          $i++; next;
        }
        # string
        my $close = ($multi ? "\"\"\"" : "\"") . ("#" x $hashes);
        if (substr($src, $i, length $close) eq $close) { $i += length $close; $mode = "code"; next; }
        if ($c eq "\\" && substr($src, $i + 1, $hashes) eq ("#" x $hashes)) {
          my $nx = substr($src, $i + 1 + $hashes, 1);
          if ($nx eq "(") {
            push @saved, { hashes => $hashes, multi => $multi, depth => $depth + 1, outer => $depth };
            $depth = $depth + 1; $mode = "code"; $i += 2 + $hashes; next;
          }
          $i += 2 + $hashes; next;
        }
        if (!$multi && $c eq "\n") { $mode = "code"; next; }
        $hit{$line} = 1 if $c =~ /\p{sc=Han}/;
        $i++;
      }
      for my $ln (sort { $a <=> $b } keys %hit) {
        my $t = $lines[$ln - 1];
        next if $t =~ /NSLog\(|precondition\(|assertionFailure\(|fatalError\(|\/\/\s*l10n-ignore:/;
        (my $s = $t) =~ s/^\s+//;
        print "$file:$ln: $s\n"; $bad = 1;
      }
    }
    exit $bad;
  '
}

if [[ "${1:-}" == "--self-test" ]]; then
  fx="$here/l10n-guard-fixtures"
  rc=0
  while IFS= read -r -d '' f; do
    if printf '%s\0' "$f" | scan >/dev/null; then
      echo "self-test FAIL: bad fixture not flagged: $f"; rc=1
    fi
  done < <(find "$fx/bad" -name '*.swift' -print0)
  if ! find "$fx/good" -name '*.swift' -print0 | scan; then
    echo "self-test FAIL: good fixture flagged"; rc=1
  fi
  [[ $rc -eq 0 ]] && echo "l10n guard self-test OK"
  exit $rc
fi

cd "$root"
if find ios/ChencangCompanion ios/ChencangAction ios/ChencangShared/Sources -name '*.swift' \
     -not -path '*/ChencangShared/UAT/*' -not -path '*/ChencangShared/L10n/*' \
     -not -path '*/ChencangShared/Generated/*' -not -name 'UATThreadStress.swift' -print0 | scan; then
  echo "l10n guard OK"
else
  echo "UI text must come from design/strings/*.json (cd design && npm run strings); use // l10n-ignore: <reason> for non-UI data" >&2
  exit 1
fi
