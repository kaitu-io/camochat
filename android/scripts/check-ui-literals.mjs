// Guard: Android UI source must not contain Han characters inside string or
// char literals. UI text comes from design/strings/*.json -> generated resources.
// Usage: node android/scripts/check-ui-literals.mjs [repoRoot]
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HAN = /\p{Script=Han}/u;

export function findHanLiterals(source) {
  const found = [];
  const n = source.length;

  const lineAt = (idx) => {
    let line = 1;
    for (let k = 0; k < idx; k++) if (source[k] === '\n') line++;
    return line;
  };

  // Scans a string body starting after the opening delimiter. Returns the
  // index just past the closing delimiter.
  function scanString(start, i, raw) {
    let literalText = '';
    while (i < n) {
      const c = source[i];
      if (raw) {
        if (source.startsWith('"""', i)) {
          i += 3;
          while (source[i] === '"') i++; // extra quotes belong to the content
          break;
        }
      } else if (c === '\\') {
        literalText += source.slice(i, i + 2);
        i += 2;
        continue;
      } else if (c === '"') {
        i++;
        break;
      } else if (c === '\n') {
        break; // unterminated; bail out
      }
      if (c === '$' && source[i + 1] === '{') {
        i = scanCode(i + 2, true);
        continue;
      }
      literalText += c;
      i++;
    }
    if (HAN.test(literalText)) {
      found.push({ line: lineAt(start), text: source.slice(start, i) });
    }
    return i;
  }

  // Scans code; when inTemplate, stops after the matching closing brace.
  function scanCode(i, inTemplate) {
    let depth = 0;
    while (i < n) {
      const c = source[i];
      if (c === '/' && source[i + 1] === '/') {
        while (i < n && source[i] !== '\n') i++;
      } else if (c === '/' && source[i + 1] === '*') {
        let nest = 1;
        i += 2;
        while (i < n && nest > 0) {
          if (source.startsWith('/*', i)) { nest++; i += 2; }
          else if (source.startsWith('*/', i)) { nest--; i += 2; }
          else i++;
        }
      } else if (c === '"') {
        if (source.startsWith('"""', i)) i = scanString(i, i + 3, true);
        else i = scanString(i, i + 1, false);
      } else if (c === "'") {
        const start = i;
        i++;
        let text = '';
        while (i < n && source[i] !== "'" && source[i] !== '\n') {
          if (source[i] === '\\') { text += source.slice(i, i + 2); i += 2; }
          else { text += source[i]; i++; }
        }
        if (source[i] === "'") i++;
        if (HAN.test(text)) found.push({ line: lineAt(start), text: source.slice(start, i) });
      } else if (c === '{') {
        depth++;
        i++;
      } else if (c === '}') {
        if (inTemplate && depth === 0) return i + 1;
        depth--;
        i++;
      } else {
        i++;
      }
    }
    return i;
  }

  scanCode(0, false);
  return found.sort((a, b) => a.line - b.line);
}

function walk(dir, out) {
  let entries;
  try { entries = readdirSync(dir); } catch { return out; }
  for (const name of entries) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.kt')) out.push(p);
  }
  return out;
}

function main() {
  const root = resolve(process.argv[2] ?? '.');
  const files = [
    ...walk(join(root, 'android/app/src/main'), []),
    ...walk(join(root, 'android/app/src/direct'), []),
    ...walk(join(root, 'android/app/src/play'), []),
    ...walk(join(root, 'android/shared/src/main'), []),
  ];
  let bad = 0;
  for (const f of files) {
    for (const hit of findHanLiterals(readFileSync(f, 'utf8'))) {
      console.log(`${relative(root, f)}:${hit.line}: ${hit.text}`);
      bad++;
    }
  }
  if (bad > 0) {
    console.error(`${bad} Han literal(s) in Android UI source`);
    process.exit(1);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
