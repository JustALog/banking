import { useEffect, useRef, useState, type ReactNode } from 'react';
import { STATUS, dateTime, groupByDay, reasonText, sagaSteps, shortId } from './format.ts';
import type { Load } from './hooks.ts';
import type { Message, Transfer, TransferStatus } from './types.ts';

export function Loaded<T>({ state, children, empty, isEmpty = (d) => Array.isArray(d) && d.length === 0 }: {
  state: Load<T>;
  children: (data: T) => ReactNode;
  empty?: ReactNode;
  isEmpty?: (data: T | null) => boolean;
}) {
  if (state.loading && !state.data) return <p className="muted" role="status">Đang tải…</p>;
  if (state.error && !state.data) return <p className="alert bad" role="alert">{state.error.message}</p>;
  if (empty && isEmpty(state.data)) return empty;
  return children(state.data as T);
}

export const MoreButton = ({ list }: { list: { more: boolean; loading: boolean; loadMore: () => Promise<void> } }) =>
  list.more ? <div><button type="button" className="btn btn-frost" onClick={list.loadMore} disabled={list.loading}>Tải thêm</button></div> : null;

export const EmptyState = ({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) => (
  <div className="empty">
    <h3>{title}</h3>
    {children && <p>{children}</p>}
    {action}
  </div>
);

/**
 * Money is the loudest type on every screen: light figures, the currency code set small beside them.
 * `sign` is '+', '−' or omitted; `size` is 'sm' (lists), 'md' (cards) or 'lg' (page hero).
 */
export function Amount({ value, currency, sign, size = 'sm', struck = false }: {
  value: number | string;
  currency: string;
  sign?: '+' | '−';
  size?: 'sm' | 'md' | 'lg';
  struck?: boolean;
}) {
  const n = Number(value).toLocaleString('vi-VN');
  return (
    <span className={`amt amt-${size} ${sign === '+' ? 'in' : ''} ${struck ? 'struck' : ''}`}>
      <span className="amt-n">{sign}{n}</span>
      <span className="amt-c">{currency}</span>
    </span>
  );
}

export function StatusBadge({ status }: { status: TransferStatus }) {
  const s = STATUS[status] ?? { label: status, tone: '' };
  return <span className={`badge ${s.tone}`}>{s.label}</span>;
}

export const StatBlock = ({ label, value }: { label: string; value: ReactNode }) => (
  <div className="stat">
    <p className="stat-value">{value}</p>
    <p className="label">{label}</p>
  </div>
);

export const PageHead = ({ title, lead, children }: { title: string; lead?: string; children?: ReactNode }) => (
  <header className="page-head">
    <div>
      <h1>{title}</h1>
      {lead && <p className="muted">{lead}</p>}
    </div>
    {children && <div className="row">{children}</div>}
  </header>
);

export function CopyButton({ text, label = 'Sao chép', className = 'btn btn-frost small' }: { text: string; label?: string; className?: string }) {
  const [done, setDone] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setDone(true);
      setTimeout(() => setDone(false), 1500);
    } catch { /* clipboard blocked: the id stays visible to copy by hand */ }
  };
  return <button type="button" className={className} onClick={copy}>{done ? 'Đã sao chép' : label}</button>;
}

/** Day headings over a list; `children` renders one item. */
export function DayGroups<T extends { createdAt: string }>({ items, children }: { items: T[]; children: (item: T) => ReactNode }) {
  return groupByDay(items).map(([label, group]) => (
    <section className="day" key={label} aria-label={label}>
      <h3 className="day-label">{label}</h3>
      <ul className="rows">{group.map(children)}</ul>
    </section>
  ));
}

/** Native modal: Escape and the cancel button both call onCancel; the browser returns focus to the opener. */
export function ConfirmDialog({ open, title, children, confirmLabel, busy, onConfirm, onCancel }: {
  open: boolean;
  title: string;
  children?: ReactNode;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} onCancel={(e) => { e.preventDefault(); if (!busy) onCancel(); }} aria-labelledby="dialog-title">
      <h2 id="dialog-title">{title}</h2>
      <div className="muted dialog-body">{children}</div>
      <div className="row dialog-actions">
        <button type="button" className="btn btn-frost" onClick={onCancel} disabled={busy}>Huỷ</button>
        <button type="button" className="btn btn-primary" onClick={onConfirm} disabled={busy}>{busy ? 'Đang xử lý…' : confirmLabel}</button>
      </div>
    </dialog>
  );
}

export function Toast({ message, onClose }: { message: Message | null; onClose: () => void }) {
  useEffect(() => {
    if (!message) return undefined;
    const t = setTimeout(onClose, 4000);
    return () => clearTimeout(t);
  }, [message, onClose]);
  return <div role="status" aria-live="polite">{message && <p className={`toast ${message.tone}`}>{message.text}</p>}</div>;
}

/** Where the transfer is in its journey; the failed step carries the reason. */
export function SagaTimeline({ transfer }: { transfer: Pick<Transfer, 'status' | 'failureReason'> }) {
  return (
    <ol className="saga">
      {sagaSteps(transfer).map((s) => (
        <li key={s.label} className={s.state}>
          <span className="saga-dot" aria-hidden="true" />
          <span>
            {s.label}
            <span className="sr-only">
              {{ done: ': xong', active: ': đang chạy', failed: ': dừng ở đây', todo: ': chưa tới' }[s.state]}
            </span>
          </span>
        </li>
      ))}
    </ol>
  );
}

/** One transfer as a live receipt. */
export function Receipt({ transfer, from, to }: {
  transfer: Pick<Transfer, 'amount' | 'currency' | 'status' | 'sourceAccountId' | 'destAccountId'> & Partial<Transfer>;
  from?: string;
  to?: string;
}) {
  return (
    <article className="receipt" aria-live="polite">
      <div className="receipt-top">
        <Amount value={transfer.amount} currency={transfer.currency} size="lg" struck={transfer.status === 'FAILED'} />
        <StatusBadge status={transfer.status} />
      </div>
      <SagaTimeline transfer={transfer} />
      {transfer.failureReason && <p className="receipt-reason">{reasonText(transfer.failureReason)}</p>}
      <dl className="summary">
        <div><dt>Từ</dt><dd className="acct">{from ?? shortId(transfer.sourceAccountId)}</dd></div>
        <div><dt>Đến</dt><dd className="acct">{to ?? shortId(transfer.destAccountId)}</dd></div>
        {transfer.createdAt && <div><dt>Thời gian</dt><dd>{dateTime(transfer.createdAt)}</dd></div>}
      </dl>
    </article>
  );
}
