import { useState } from 'react';
import type { Balance } from './api';
import { InvariantsBadge } from './components/InvariantsBadge';
import { Participants } from './components/Participants';
import { Settlements } from './components/Settlements';
import { StatementPanel } from './components/StatementPanel';
import { Transfers } from './components/Transfers';

const tabs = ['Participants', 'Settlements', 'Transfers'] as const;
type Tab = (typeof tabs)[number];

export function App() {
  const [tab, setTab] = useState<Tab>('Participants');
  const [account, setAccount] = useState<Balance | null>(null);

  return (
    <div className="app">
      <header className="topbar">
        <h1>settlement-ledger</h1>
        <InvariantsBadge />
      </header>
      <nav className="tabs" role="tablist">
        {tabs.map((t) => (
          <button key={t} role="tab" aria-selected={t === tab} onClick={() => setTab(t)}>
            {t}
          </button>
        ))}
      </nav>
      <main>
        {tab === 'Participants' && <Participants onOpenAccount={setAccount} />}
        {tab === 'Settlements' && <Settlements />}
        {tab === 'Transfers' && <Transfers />}
      </main>
      {account && <StatementPanel accountId={account.accountId} onClose={() => setAccount(null)} />}
    </div>
  );
}
