// Typed client for the ledger API. The shapes mirror the Java records.

export type Balance = { accountId: number; asset: string; balance: number };

export type Participant = { id: string; name: string; iban: string; balances: Balance[] };

export type SettlementStatus = 'PENDING' | 'SETTLED' | 'FAILED';

export type Settlement = {
  tradeId: string;
  symbol: string;
  priceCents: number;
  quantity: number;
  buyer: string;
  seller: string;
  status: SettlementStatus;
  failureReason: string | null;
  notionalCents: number | null;
  feeCents: number | null;
  entryId: number | null;
  executedAt: string;
  settledAt: string | null;
};

export type TransferStatus = 'RECEIVED' | 'VALIDATED' | 'SETTLED' | 'REJECTED';

export type Transfer = {
  id: string;
  direction: 'OUTBOUND' | 'INBOUND';
  participantId: string | null;
  amountCents: number;
  currency: string;
  counterpartyIban: string;
  counterpartyName: string;
  endToEndId: string;
  status: TransferStatus;
  rejectReason: string | null;
  createdAt: string;
  settledAt: string | null;
  events: { status: TransferStatus; reason: string | null; at: string }[];
};

export type NewTransfer = {
  participantId: string;
  amount: string;
  creditorName: string;
  creditorIban: string;
  remittanceInformation?: string;
};

export type StatementLine = {
  postingId: number;
  entryId: number;
  kind: string;
  reference: string;
  amount: number;
  balanceAfter: number;
  at: string;
};

export type Statement = {
  accountId: number;
  participantId: string | null;
  asset: string;
  balance: number;
  lines: StatementLine[];
  nextAfterId: number | null;
};

export type Invariants = {
  ok: boolean;
  unbalancedEntries: number;
  accountsWithDrift: number;
  assetImbalances: Record<string, number>;
  outboxBacklog: number;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    let detail = response.statusText;
    try {
      const problem = await response.json();
      detail = problem.detail ?? detail;
    } catch {
      // not a problem+json body
    }
    throw new ApiError(response.status, detail);
  }
  return (await response.json()) as T;
}

export const api = {
  participants: () => request<Participant[]>('/api/participants'),
  settlements: (status?: SettlementStatus) =>
    request<Settlement[]>(`/api/settlements?limit=50${status ? `&status=${status}` : ''}`),
  transfers: () => request<Transfer[]>('/api/transfers?limit=50'),
  transfer: (id: string) => request<Transfer>(`/api/transfers/${id}`),
  statement: (accountId: number) => request<Statement>(`/api/accounts/${accountId}/statement?limit=200`),
  invariants: () => request<Invariants>('/api/admin/invariants'),
  createTransfer: (body: NewTransfer, idempotencyKey: string) =>
    request<Transfer>('/api/transfers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body),
    }),
};
