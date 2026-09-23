/**
 * Maintenance for the shared AI fixtures in data/ai/fixtures (the iOS/Android/backend parity lock).
 *
 *   npm run ai:fixtures                 check every fixture against the TypeScript reference (default)
 *   npm run ai:fixtures -- --prompts    regenerate prompt-*.json after editing prompts, the table or schemas
 *   npm run ai:fixtures -- --write      rewrite every expected value from the reference
 *
 * Use --write only after a deliberate change to the algorithm or to spec numbers, and review the diff:
 * the iOS and Android ports must then be updated to produce the same values.
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { FIXTURES_DIR, loadSpec } from '../src/aiSpec.js';
import { checkFixture, produce, promptExpectation, readFixtures, type Fixture } from './ai-fixtures-lib.js';

const spec = loadSpec();
const args = new Set(process.argv.slice(2));

const PROMPT_FIXTURES: Array<{ file: string; fixture: Fixture }> = [
  {
    file: 'prompt-pl.json',
    fixture: {
      kind: 'prompt',
      name: 'Prompt assembly, Polish',
      description: 'System instructions, request texts, schemas (canonical and Gemini-stripped) and notes context for a pl-PL dinner with notes that need JSON escaping.',
      input: { locale: 'pl-PL', meal: 'dinner', notes: 'Zjadłem 6 pierogów "z okrasą"\nwaga: 250 g / talerz 26 cm\ttab \\ backslash' },
    },
  },
  {
    file: 'prompt-en.json',
    fixture: {
      kind: 'prompt',
      name: 'Prompt assembly, English, empty notes',
      description: 'en-GB breakfast with empty notes: the notes placeholder becomes "".',
      input: { locale: 'en-GB', meal: 'breakfast', notes: '' },
    },
  },
];

function write(file: string, fixture: Fixture): void {
  writeFileSync(join(FIXTURES_DIR, file), `${JSON.stringify(fixture, null, 2)}\n`);
}

if (args.has('--prompts') || args.has('--write')) {
  for (const { file, fixture } of PROMPT_FIXTURES) {
    write(file, { ...fixture, expected: promptExpectation(spec, fixture.input!) });
    console.log(`wrote ${file}`);
  }
}

if (args.has('--write')) {
  for (const { file, fixture } of readFixtures(FIXTURES_DIR)) {
    if (fixture.kind === 'prompt') continue;
    const got = produce(fixture, spec);
    const next: Fixture = { ...fixture };
    delete next.expected;
    delete next.expectedError;
    if (fixture.kind === 'context') next.cases = got.cases as Fixture['cases'];
    else if (got.expectedError !== undefined) next.expectedError = got.expectedError;
    else next.expected = got.expected;
    write(file, next);
    console.log(`wrote ${file}`);
  }
}

let failures = 0;
for (const { file, fixture } of readFixtures(FIXTURES_DIR)) {
  const diff = checkFixture(fixture, spec);
  if (diff) {
    failures++;
    console.error(`FAIL ${file}: ${diff}`);
  }
}
console.log(failures === 0 ? 'all AI fixtures match the reference' : `${failures} fixture(s) differ`);
process.exit(failures === 0 ? 0 : 1);
