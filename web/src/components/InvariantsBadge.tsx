import { api } from '../api';
import { usePolling } from '../usePolling';

/** Green while every ledger invariant holds; red (with the details) the moment one breaks. */
export function InvariantsBadge() {
  const { data, error } = usePolling(api.invariants, 5000);
  if (error) return <span className="badge badge-bad">ledger unreachable</span>;
  if (!data) return null;
  if (data.ok) {
    return (
      <span className="badge badge-ok" title={`outbox backlog: ${data.outboxBacklog}`}>
        invariants hold
      </span>
    );
  }
  return (
    <span className="badge badge-bad" title={JSON.stringify(data)}>
      INVARIANT BROKEN
    </span>
  );
}
