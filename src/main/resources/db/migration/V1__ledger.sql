-- Double-entry ledger. Amounts are signed integers in minor units (euro cents for EUR,
-- shares for an instrument). An account's balance is the sum of its postings, and every
-- journal entry sums to zero per asset, so every asset sums to zero across all accounts.

create table participant (
    id         text primary key check (id ~ '^[A-Za-z0-9_-]{1,32}$'),
    name       text not null check (length(name) between 1 and 70),
    iban       text not null unique,
    created_at timestamptz not null default now()
);

-- Account numbers for the participants' virtual IBANs.
create sequence participant_iban_seq start 1000001;

create table account (
    id             bigserial primary key,
    participant_id text references participant (id),
    code           text unique,
    asset          text    not null,
    allow_negative boolean not null default false,
    balance        bigint  not null default 0,
    created_at     timestamptz not null default now(),
    unique (participant_id, asset),
    unique (id, asset),
    -- a client account belongs to a participant; a system account has a code instead
    check ((participant_id is null) <> (code is null)),
    check (allow_negative or balance >= 0)
);

create table journal_entry (
    id          bigserial primary key,
    kind        text not null,
    reference   text not null,
    description text,
    created_at  timestamptz not null default now(),
    unique (kind, reference)
);

create table posting (
    id            bigserial primary key,
    entry_id      bigint not null references journal_entry (id),
    account_id    bigint not null,
    asset         text   not null,
    amount        bigint not null check (amount <> 0),
    balance_after bigint not null,
    -- a posting's asset must be its account's asset
    foreign key (account_id, asset) references account (id, asset)
);
create index posting_account_idx on posting (account_id, id);
create index posting_entry_idx on posting (entry_id);

-- Every entry balances per asset. The check is deferred to commit time, when all the
-- entry's postings are in; a transaction that breaks the rule cannot commit.
create function check_entry_balanced() returns trigger
    language plpgsql as
$$
begin
    if exists (select 1
               from posting
               where entry_id = new.entry_id
               group by asset
               having sum(amount) <> 0) then
        raise exception 'journal entry % does not balance', new.entry_id using errcode = '23514';
    end if;
    return null;
end
$$;

create constraint trigger posting_balanced
    after insert on posting
    deferrable initially deferred
    for each row execute function check_entry_balanced();

-- The journal is append-only: mistakes are fixed with a new, reversing entry.
create function forbid_change() returns trigger
    language plpgsql as
$$
begin
    raise exception '% is append-only', tg_table_name using errcode = '42501';
end
$$;

create trigger journal_entry_append_only before update or delete on journal_entry
    for each row execute function forbid_change();
create trigger journal_entry_no_truncate before truncate on journal_entry
    for each statement execute function forbid_change();
create trigger posting_append_only before update or delete on posting
    for each row execute function forbid_change();
create trigger posting_no_truncate before truncate on posting
    for each statement execute function forbid_change();

-- System accounts. SEPA_CLEARING is the mirror of the money held at the clearing bank:
-- its balance is minus the cash the ledger owes to participants, so it may go negative.
insert into account (code, asset, allow_negative)
values ('SEPA_CLEARING', 'EUR', true),
       ('FEES', 'EUR', false);

-- Trade settlement (delivery versus payment). One row per trade_id: a redelivered
-- message finds the row and does nothing.
create table trade_settlement (
    trade_id       text primary key,
    symbol         text        not null,
    price_cents    bigint      not null check (price_cents > 0),
    quantity       bigint      not null check (quantity > 0),
    buyer          text        not null,
    seller         text        not null,
    executed_at    timestamptz not null,
    status         text        not null check (status in ('PENDING', 'SETTLED', 'FAILED')),
    failure_reason text,
    notional_cents bigint,
    fee_cents      bigint,
    entry_id       bigint references journal_entry (id),
    received_at    timestamptz not null default now(),
    settled_at     timestamptz
);
create index trade_settlement_recent_idx on trade_settlement (received_at desc);

-- SEPA credit transfers, outbound (to another bank) and inbound (from another bank).
create table transfer (
    id                uuid primary key,
    direction         text        not null check (direction in ('OUTBOUND', 'INBOUND')),
    participant_id    text references participant (id),
    amount_cents      bigint      not null check (amount_cents > 0),
    currency          text        not null,
    counterparty_iban text        not null,
    counterparty_name text        not null,
    counterparty_bic  text,
    remittance_info   text,
    end_to_end_id     text        not null,
    idempotency_key   text,
    request_hash      text,
    status            text        not null check (status in ('RECEIVED', 'VALIDATED', 'SETTLED', 'REJECTED')),
    reject_reason     text,
    entry_id          bigint references journal_entry (id),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    settled_at        timestamptz,
    unique (participant_id, idempotency_key)
);
create unique index transfer_outbound_e2e_idx on transfer (participant_id, end_to_end_id) where direction = 'OUTBOUND';
create unique index transfer_inbound_e2e_idx on transfer (counterparty_iban, end_to_end_id) where direction = 'INBOUND';
create index transfer_validated_idx on transfer (created_at) where status = 'VALIDATED';
create index transfer_recent_idx on transfer (created_at desc);
create index transfer_settled_idx on transfer (settled_at) where status = 'SETTLED';

create table transfer_event (
    id          bigserial primary key,
    transfer_id uuid        not null references transfer (id),
    status      text        not null,
    reason      text,
    at          timestamptz not null default now()
);
create index transfer_event_transfer_idx on transfer_event (transfer_id, id);

-- Transactional outbox: events are written in the same transaction as the ledger change
-- and published to Kafka afterwards by OutboxRelay.
create table outbox (
    id             bigserial primary key,
    event_id       uuid        not null unique,
    aggregate_type text        not null,
    aggregate_id   text        not null,
    event_type     text        not null,
    payload        jsonb       not null,
    created_at     timestamptz not null default now(),
    published_at   timestamptz
);
create index outbox_unpublished_idx on outbox (id) where published_at is null;
