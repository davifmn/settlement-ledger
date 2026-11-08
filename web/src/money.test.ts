import { describe, expect, it } from 'vitest';
import { formatAmount, parseEuroCents } from './money';

describe('parseEuroCents', () => {
  it('reads decimals exactly, without floating point', () => {
    expect(parseEuroCents('250')).toBe(25000);
    expect(parseEuroCents('250.5')).toBe(25050);
    expect(parseEuroCents('0.1')).toBe(10);
    expect(parseEuroCents(' 19.99 ')).toBe(1999);
  });

  it('refuses what the server would refuse', () => {
    for (const bad of ['', '0', '0.00', '-1', '1.234', '1,50', 'abc', '1e3']) {
      expect(parseEuroCents(bad), bad).toBeNull();
    }
  });
});

describe('formatAmount', () => {
  it('formats euros from cents and securities as units', () => {
    expect(formatAmount(123456, 'EUR')).toBe('€1,234.56');
    expect(formatAmount(-500, 'EUR')).toBe('-€5.00');
    expect(formatAmount(1500, 'PETR4')).toBe('1,500 PETR4');
  });
});
