import { useState } from 'react';
import { api, type Transfer } from '../api';
import { formatAmount } from '../money';
import { usePolling } from '../usePolling';
import { StatusBadge } from './StatusBadge';
import { TransferForm } from './TransferForm';

export function Transfers() {
  const transfers = usePolling(api.transfers, 2000);
  const participants = usePolling(api.participants, 10000);
  const [selected, setSelected] = useState<Transfer | null>(null);

  return (
    <section>
      <h2>SEPA transfers</h2>
      {participants.data && participants.data.length > 0 && (
        <TransferForm participants={participants.data} onCreated={() => void transfers.reload()} />
      )}
      {transfers.error && <p className="error">Could not load transfers: {transfers.error}</p>}
      {transfers.data && (
        <table>
          <thead>
            <tr>
              <th>Direction</th>
              <th>Participant</th>
              <th className="num">Amount</th>
              <th>Counterparty</th>
              <th>End-to-end id</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {transfers.data.map((t) => (
              <tr key={t.id} className="clickable" onClick={() => setSelected(t)}>
                <td>{t.direction === 'OUTBOUND' ? 'out' : 'in'}</td>
                <td className="mono">{t.participantId ?? '—'}</td>
                <td className="num">{formatAmount(t.amountCents, 'EUR')}</td>
                <td>
                  {t.counterpartyName} <span className="mono muted">{t.counterpartyIban}</span>
                </td>
                <td className="mono truncate">{t.endToEndId}</td>
                <td>
                  <StatusBadge status={t.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {selected && <TransferDetail id={selected.id} onClose={() => setSelected(null)} />}
    </section>
  );
}

function TransferDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const { data } = usePolling(() => api.transfer(id), 2000);
  if (!data) return null;
  return (
    <aside className="panel" aria-label="Transfer history">
      <header className="panel-header">
        <h2>Transfer {data.endToEndId}</h2>
        <button onClick={onClose} aria-label="Close transfer">
          ×
        </button>
      </header>
      <ol className="timeline">
        {data.events.map((e, i) => (
          <li key={i}>
            <StatusBadge status={e.status} /> {new Date(e.at).toLocaleTimeString()}
            {e.reason && <div className="muted">{e.reason}</div>}
          </li>
        ))}
      </ol>
    </aside>
  );
}
