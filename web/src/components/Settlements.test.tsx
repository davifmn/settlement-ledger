import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Settlement } from '../api';
import { Settlements } from './Settlements';

const rows: Settlement[] = [
  {
    tradeId: 'PETR4-981',
    symbol: 'PETR4',
    priceCents: 3415,
    quantity: 300,
    buyer: 'alice',
    seller: 'bob',
    status: 'SETTLED',
    failureReason: null,
    notionalCents: 1024500,
    feeCents: 205,
    entryId: 7,
    executedAt: '2026-11-08T10:15:30Z',
    settledAt: '2026-11-08T10:15:30.040Z',
  },
  {
    tradeId: 'PETR4-982',
    symbol: 'PETR4',
    priceCents: 3416,
    quantity: 50,
    buyer: 'carol',
    seller: 'bob',
    status: 'FAILED',
    failureReason: 'insufficient_cash',
    notionalCents: null,
    feeCents: null,
    entryId: null,
    executedAt: '2026-11-08T10:15:31Z',
    settledAt: null,
  },
];

describe('Settlements', () => {
  it('shows settled and failed trades with their detail', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response(JSON.stringify(rows), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );

    render(<Settlements />);

    expect(await screen.findByText('PETR4-981')).toBeInTheDocument();
    expect(screen.getByText('€10,245.00, fee €2.05/side')).toBeInTheDocument();
    expect(screen.getByText('insufficient_cash')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('says so when the API is down', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('{"detail":"boom"}', { status: 503 }));

    render(<Settlements />);

    expect(await screen.findByText(/Could not load settlements: boom/)).toBeInTheDocument();
  });
});
