import { useCallback, useState } from 'react';
import { api, type SettlementStatus } from '../api';
import { formatAmount } from '../money';
import { usePolling } from '../usePolling';
import { StatusBadge } from './StatusBadge';

export function Settlements() {
  const [filter, setFilter] = useState<SettlementStatus | ''>('');
  const load = useCallback(() => api.settlements(filter || undefined), [filter]);
  const { data, error } = usePolling(load, 2000);

  return (
    <section>
      <div className="section-header">
        <h2>Trade settlements</h2>
        <label>
          Status{' '}
          <select value={filter} onChange={(e) => setFilter(e.target.value as SettlementStatus | '')}>
            <option value="">all</option>
            <option value="SETTLED">settled</option>
            <option value="FAILED">failed</option>
          </select>
        </label>
      </div>
      {error && <p className="error">Could not load settlements: {error}</p>}
      {data && data.length === 0 && <p className="muted">No trades yet. Publish to trades.executed or use the simulator.</p>}
      {data && data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Trade</th>
              <th>Symbol</th>
              <th className="num">Qty</th>
              <th className="num">Price</th>
              <th>Seller → Buyer</th>
              <th>Status</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {data.map((s) => (
              <tr key={s.tradeId}>
                <td className="mono truncate">{s.tradeId}</td>
                <td>{s.symbol}</td>
                <td className="num">{s.quantity}</td>
                <td className="num">{formatAmount(s.priceCents, 'EUR')}</td>
                <td className="mono">
                  {s.seller} → {s.buyer}
                </td>
                <td>
                  <StatusBadge status={s.status} />
                </td>
                <td>
                  {s.status === 'SETTLED' && s.notionalCents !== null
                    ? `${formatAmount(s.notionalCents, 'EUR')}, fee ${formatAmount(s.feeCents ?? 0, 'EUR')}/side`
                    : s.failureReason}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
