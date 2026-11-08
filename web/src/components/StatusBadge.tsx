const tone: Record<string, string> = {
  SETTLED: 'ok',
  VALIDATED: 'info',
  RECEIVED: 'info',
  PENDING: 'info',
  FAILED: 'bad',
  REJECTED: 'bad',
};

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge badge-${tone[status] ?? 'info'}`}>{status}</span>;
}
