// Amounts travel as integer minor units (cents, shares) and are only formatted at the edge.

const euro = new Intl.NumberFormat('en-IE', { style: 'currency', currency: 'EUR' });
const units = new Intl.NumberFormat('en-IE');

export function formatAmount(minorUnits: number, asset: string): string {
  return asset === 'EUR' ? euro.format(minorUnits / 100) : `${units.format(minorUnits)} ${asset}`;
}

/** "250.5" → 25050. Returns null for anything the server would refuse. */
export function parseEuroCents(input: string): number | null {
  const value = input.trim();
  if (!/^\d{1,13}(\.\d{1,2})?$/.test(value)) {
    return null;
  }
  const [whole, fraction = ''] = value.split('.');
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  return cents > 0 ? cents : null;
}
