import { describe, expect, it } from 'vitest';
import { PAIR_CODE_CHARSET, PAIR_CODE_RE, generatePairCode, normalizePairCode } from '../src/pairing.js';

describe('generatePairCode', () => {
  it('produces NT- plus four characters from the unambiguous charset', () => {
    for (let i = 0; i < 500; i++) {
      const code = generatePairCode();
      expect(code).toMatch(PAIR_CODE_RE);
      for (const ch of code.slice(3)) expect(PAIR_CODE_CHARSET).toContain(ch);
    }
    expect(PAIR_CODE_CHARSET).toHaveLength(32);
    for (const banned of ['0', 'O', '1', 'I']) expect(PAIR_CODE_CHARSET).not.toContain(banned);
  });

  it('maps bytes onto the charset uniformly (masking to 5 bits)', () => {
    expect(generatePairCode(() => Uint8Array.from([0, 1, 31, 32]))).toBe('NT-AB9A');
    expect(generatePairCode(() => Uint8Array.from([255, 254, 253, 252]))).toBe('NT-9876');
  });

  it('is not obviously repetitive', () => {
    const codes = new Set(Array.from({ length: 200 }, () => generatePairCode()));
    expect(codes.size).toBeGreaterThan(190);
  });
});

describe('normalizePairCode', () => {
  it('uppercases, strips whitespace and tolerates a missing prefix', () => {
    expect(normalizePairCode('nt-7k4q')).toBe('NT-7K4Q');
    expect(normalizePairCode(' 7K4Q ')).toBe('NT-7K4Q');
    expect(normalizePairCode('NT 7K4Q')).toBe('NT-7K4Q');
    expect(normalizePairCode('nt7k4q')).toBe('NT-7K4Q');
  });

  it('rejects anything outside the charset or wrong length', () => {
    expect(normalizePairCode('NT-7K4O')).toBeNull();
    expect(normalizePairCode('NT-7K4')).toBeNull();
    expect(normalizePairCode('NT-7K4QQ')).toBeNull();
    expect(normalizePairCode('')).toBeNull();
  });
});
