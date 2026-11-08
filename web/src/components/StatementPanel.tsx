import { useCallback } from 'react';
import { api } from '../api';
import { formatAmount } from '../money';
import { usePolling } from '../usePolling';

export function StatementPanel({ accountId, onClose }: { accountId: number; onClose: () => void }) {
  const load = useCallback(() => api.statement(accountId), [accountId]);
  const { data, error } = usePolling(load);

  return (
    <aside className="panel" aria-label="Account statement">
      <header className="panel-header">
        <h2>
          Account {accountId}
          {data && ` · ${data.participantId ?? 'system'} · ${data.asset}`}
        </h2>
        <button onClick={onClose} aria-label="Close statement">
          ×
        </button>
      </header>
      {error && <p className="error">{error}</p>}
      {data && (
        <>
          <p className="balance">{formatAmount(data.balance, data.asset)}</p>
          <table>
            <thead>
              <tr>
                <th>Entry</th>
                <th>Kind</th>
                <th>Reference</th>
                <th className="num">Amount</th>
                <th className="num">Balance after</th>
              </tr>
            </thead>
            <tbody>
              {data.lines.map((line) => (
                <tr key={line.postingId}>
                  <td className="mono">{line.entryId}</td>
                  <td>{line.kind}</td>
                  <td className="mono truncate">{line.reference}</td>
                  <td className={`num ${line.amount < 0 ? 'neg' : 'pos'}`}>{formatAmount(line.amount, data.asset)}</td>
                  <td className="num">{formatAmount(line.balanceAfter, data.asset)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </aside>
  );
}
