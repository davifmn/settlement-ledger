import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Participant } from '../api';
import { TransferForm } from './TransferForm';

const participants: Participant[] = [{ id: 'alice', name: 'Alice', iban: 'DE43999999990001000001', balances: [] }];

function okResponse(body: unknown) {
  return new Response(JSON.stringify(body), { status: 201, headers: { 'Content-Type': 'application/json' } });
}

async function fill(user: ReturnType<typeof userEvent.setup>, iban: string) {
  await user.type(screen.getByLabelText('Amount (EUR)'), '250.00');
  await user.type(screen.getByLabelText('Creditor name'), 'Berlin Office Supplies');
  await user.type(screen.getByLabelText('Creditor IBAN'), iban);
}

describe('TransferForm', () => {
  it('does not call the server when the IBAN is wrong', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');
    const user = userEvent.setup();
    render(<TransferForm participants={participants} onCreated={() => {}} />);

    await fill(user, 'DE89370400440532013001');
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Creditor IBAN is not valid');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('sends an Idempotency-Key and reuses it when the user retries after a failure', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(okResponse({ id: 't1', status: 'VALIDATED' }));
    const onCreated = vi.fn();
    const keys = ['key-1', 'key-2'];
    const user = userEvent.setup();
    render(<TransferForm participants={participants} onCreated={onCreated} newKey={() => keys.shift()!} />);

    await fill(user, 'DE89 3704 0044 0532 0130 00');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot pay twice');

    await user.click(screen.getByRole('button', { name: 'Retry' }));

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const headers = fetchMock.mock.calls.map(([, init]) => (init?.headers as Record<string, string>)['Idempotency-Key']);
    expect(headers).toEqual(['key-1', 'key-1']);
    const body = JSON.parse(fetchMock.mock.calls[1][1]?.body as string);
    expect(body).toEqual({
      participantId: 'alice',
      amount: '250.00',
      creditorName: 'Berlin Office Supplies',
      creditorIban: 'DE89370400440532013000',
    });
    expect(onCreated).toHaveBeenCalledWith({ id: 't1', status: 'VALIDATED' });
  });
});
