import { describe, expect, it } from 'vitest';
import { isValidIban, normalizeIban } from './iban';

describe('isValidIban', () => {
  it('accepts valid IBANs, with or without spaces', () => {
    expect(isValidIban('DE89370400440532013000')).toBe(true);
    expect(isValidIban('de89 3704 0044 0532 0130 00')).toBe(true);
    expect(isValidIban('CH9300762011623852957')).toBe(true);
    expect(isValidIban('FR1420041010050500013M02606')).toBe(true);
  });

  it('catches typos', () => {
    expect(isValidIban('DE89370400440532013001')).toBe(false);
    expect(isValidIban('DE98370400440532013000')).toBe(false);
    expect(isValidIban('DE89')).toBe(false);
    expect(isValidIban('')).toBe(false);
  });

  it('normalizes', () => {
    expect(normalizeIban(' de89 3704 0044 0532 0130 00 ')).toBe('DE89370400440532013000');
  });
});
