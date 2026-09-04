// Regenerates data/breached-passwords.txt from the frequency-ranked common-password
// dictionary shipped with @zxcvbn-ts/language-common (derived from the xato.net 10M corpus).
// Run with: npm run breached:build
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const TOP_N = 10_000;
const mod = await import('@zxcvbn-ts/language-common');
const dictionary = (mod.default ?? mod).dictionary;
const ranked = dictionary['passwords-common'];
if (!Array.isArray(ranked) || ranked.length < TOP_N) {
  throw new Error('passwords-common dictionary missing or too small');
}

const outDir = fileURLToPath(new URL('../data/', import.meta.url));
await mkdir(outDir, { recursive: true });
const header = [
  '# Top 10,000 most common leaked passwords, one per line, most common first.',
  '# Source: @zxcvbn-ts/language-common "passwords-common" (xato.net 10M corpus).',
  '# Regenerate with `npm run breached:build`. Lines starting with # are ignored.',
].join('\n');
await writeFile(`${outDir}breached-passwords.txt`, `${header}\n${ranked.slice(0, TOP_N).join('\n')}\n`);
console.log(`wrote ${TOP_N} entries to data/breached-passwords.txt`);
