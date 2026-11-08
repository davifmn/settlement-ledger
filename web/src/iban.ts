// Client-side IBAN check (ISO 13616 mod-97) to catch typos before they reach the server.
// The server repeats the check with the per-country lengths of the SEPA area.

export function normalizeIban(iban: string): string {
  return iban.replace(/\s+/g, '').toUpperCase();
}

export function isValidIban(input: string): boolean {
  const iban = normalizeIban(input);
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(iban)) {
    return false;
  }
  const rearranged = iban.slice(4) + iban.slice(0, 4);
  let remainder = 0;
  for (const char of rearranged) {
    const value = parseInt(char, 36);
    remainder = value < 10 ? (remainder * 10 + value) % 97 : (remainder * 100 + value) % 97;
  }
  return remainder === 1;
}
