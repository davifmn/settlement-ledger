import { api, type Balance } from '../api';
import { formatAmount } from '../money';
import { usePolling } from '../usePolling';

export function Participants({ onOpenAccount }: { onOpenAccount: (balance: Balance) => void }) {
  const { data, error } = usePolling(api.participants);

  return (
    <section>
      <h2>Participants</h2>
      {error && <p className="error">Could not load participants: {error}</p>}
      {data && data.length === 0 && <p className="muted">No participants yet. Create one with POST /api/participants.</p>}
      {data && data.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Id</th>
              <th>Name</th>
              <th>Virtual IBAN</th>
              <th>Balances (click for statement)</th>
            </tr>
          </thead>
          <tbody>
            {data.map((p) => (
              <tr key={p.id}>
                <td className="mono">{p.id}</td>
                <td>{p.name}</td>
                <td className="mono">{p.iban}</td>
                <td>
                  {p.balances.map((b) => (
                    <button key={b.accountId} className="chip" onClick={() => onOpenAccount(b)}>
                      {formatAmount(b.balance, b.asset)}
                    </button>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
