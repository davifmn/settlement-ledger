import { type FormEvent, useState } from 'react';
import { ApiError, api, type Participant, type Transfer } from '../api';
import { isValidIban, normalizeIban } from '../iban';
import { parseEuroCents } from '../money';

type Props = {
  participants: Participant[];
  onCreated: (transfer: Transfer) => void;
  newKey?: () => string;
};

/**
 * Sends an outbound transfer. The Idempotency-Key is created once per payment and reused
 * when the user retries after an error, so a timeout followed by a retry cannot pay twice.
 */
export function TransferForm({ participants, onCreated, newKey = () => crypto.randomUUID() }: Props) {
  const [participantId, setParticipantId] = useState('');
  const [amount, setAmount] = useState('');
  const [creditorName, setCreditorName] = useState('');
  const [creditorIban, setCreditorIban] = useState('');
  const [remittance, setRemittance] = useState('');
  const [key, setKey] = useState<string | null>(null);
  const [errors, setErrors] = useState<string[]>([]);
  const [sending, setSending] = useState(false);

  const payer = participantId || participants[0]?.id || '';

  function validate(): string[] {
    const problems: string[] = [];
    if (!payer) problems.push('Choose a participant.');
    if (parseEuroCents(amount) === null) problems.push('Amount must be a positive number with at most 2 decimals.');
    if (!creditorName.trim()) problems.push('Creditor name is required.');
    if (!isValidIban(creditorIban)) problems.push('Creditor IBAN is not valid.');
    return problems;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const problems = validate();
    setErrors(problems);
    if (problems.length > 0) return;

    const idempotencyKey = key ?? newKey();
    setKey(idempotencyKey);
    setSending(true);
    try {
      const transfer = await api.createTransfer(
        {
          participantId: payer,
          amount: amount.trim(),
          creditorName: creditorName.trim(),
          creditorIban: normalizeIban(creditorIban),
          remittanceInformation: remittance.trim() || undefined,
        },
        idempotencyKey,
      );
      setKey(null); // done: the next payment gets a new key
      setAmount('');
      setRemittance('');
      onCreated(transfer);
    } catch (e) {
      const message = e instanceof ApiError ? `${e.status}: ${e.message}` : 'Network error';
      setErrors([`${message}. Retrying sends the same Idempotency-Key, so it cannot pay twice.`]);
    } finally {
      setSending(false);
    }
  }

  return (
    <form className="card" onSubmit={submit} noValidate>
      <h3>New SEPA transfer</h3>
      <div className="grid">
        <label>
          From
          <select value={payer} onChange={(e) => setParticipantId(e.target.value)}>
            {participants.map((p) => (
              <option key={p.id} value={p.id}>
                {p.id}
              </option>
            ))}
          </select>
        </label>
        <label>
          Amount (EUR)
          <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" placeholder="250.00" />
        </label>
        <label>
          Creditor name
          <input value={creditorName} onChange={(e) => setCreditorName(e.target.value)} />
        </label>
        <label>
          Creditor IBAN
          <input value={creditorIban} onChange={(e) => setCreditorIban(e.target.value)} placeholder="DE89 3704 0044 0532 0130 00" />
        </label>
        <label className="wide">
          Remittance information
          <input value={remittance} onChange={(e) => setRemittance(e.target.value)} maxLength={140} />
        </label>
      </div>
      {errors.length > 0 && (
        <ul className="error" role="alert">
          {errors.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      )}
      <button type="submit" disabled={sending}>
        {key ? 'Retry' : 'Send'}
      </button>
    </form>
  );
}
