import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import type { DependencyList, ReactNode } from 'react';
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { getSession, logout, onSessionChange } from './gateway';
import { STATUS, dateTime, money, reasonText, shortId } from './format';
import type { Customer, Role, Session, Transfer, TransferStatus } from './types';

export const useSession = (): Session | null => useSyncExternalStore(onSessionChange, getSession);

/** The signed-in customer. Only for components rendered under <RequireRole>, which guarantees a session. */
export function useCustomer(): Customer {
  const session = useSession();
  if (!session) throw new Error('useCustomer() used outside <RequireRole>');
  return session.customer;
}

/** A one-line result message after an action. */
export interface Flash {
  tone: 'ok' | 'bad';
  text: string;
}

export interface LoadState<T> {
  data: T | null;
  error: Error | null;
  loading: boolean;
}

export interface Loader<T> extends LoadState<T> {
  reload: () => Promise<void>;
}

const asError = (e: unknown): Error => (e instanceof Error ? e : new Error(String(e)));

// Loads data on mount and whenever deps change; `reload` re-runs it without a spinner flash.
export function useLoad<T>(fn: () => Promise<T>, deps: DependencyList = []): Loader<T> {
  const [state, setState] = useState<LoadState<T>>({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);
  const reload = useCallback(async () => {
    try {
      const data = await run();
      setState({ data, error: null, loading: false });
    } catch (error) {
      setState((s) => ({ data: s.data, error: asError(error), loading: false }));
    }
  }, [run]);
  useEffect(() => {
    let live = true;
    setState((s) => ({ ...s, loading: true }));
    run().then(
      (data) => { if (live) setState({ data, error: null, loading: false }); },
      (error: unknown) => { if (live) setState({ data: null, error: asError(error), loading: false }); },
    );
    return () => { live = false; };
  }, [run]);
  return { ...state, reload };
}

interface LoadedProps<T> {
  state: LoadState<T>;
  children: (data: T) => ReactNode;
  empty?: ReactNode;
  isEmpty?: (data: T) => boolean;
}

const isEmptyList = (d: unknown): boolean => Array.isArray(d) && d.length === 0;

export function Loaded<T>({ state, children, empty, isEmpty = isEmptyList }: LoadedProps<T>) {
  if (state.data === null) {
    if (state.error && !state.loading) return <p className="alert bad" role="alert">{state.error.message}</p>;
    return <p className="muted">Đang tải…</p>;
  }
  if (empty && isEmpty(state.data)) return <div className="empty">{empty}</div>;
  return <>{children(state.data)}</>;
}

interface MoneyProps {
  value: number;
  currency: string;
  sign?: '+' | '−';
}

export const Money = ({ value, currency, sign }: MoneyProps) => (
  <span className={`money ${sign === '+' ? 'ok' : ''}`}>{sign}{money(value, currency)}</span>
);

export function StatusBadge({ status }: { status: TransferStatus }) {
  const s = STATUS[status] ?? { label: status, tone: '' };
  return <span className={`badge ${s.tone}`}>{s.label}</span>;
}

export function CopyButton({ text, label = 'Sao chép' }: { text: string; label?: string }) {
  const [done, setDone] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setDone(true);
      setTimeout(() => setDone(false), 1500);
    } catch { /* clipboard blocked: the id stays visible to copy by hand */ }
  };
  return <button type="button" className="ghost small" onClick={copy}>{done ? 'Đã sao chép' : label}</button>;
}

export const PageHead = ({ title, children }: { title: string; children?: ReactNode }) => (
  <header className="page-head">
    <h1>{title}</h1>
    {children && <div className="actions">{children}</div>}
  </header>
);

/** What a receipt needs: the landing page's sample has no account ids or failure reason. */
export type ReceiptTransfer = Pick<Transfer, 'id' | 'amount' | 'currency' | 'status' | 'createdAt'>
  & Partial<Pick<Transfer, 'sourceAccountId' | 'destAccountId' | 'failureReason'>>;

interface ReceiptProps {
  transfer: ReceiptTransfer;
  from?: string;
  to?: string;
}

/** The signature element: a paper receipt for one transfer. */
export function Receipt({ transfer, from, to }: ReceiptProps) {
  const s = STATUS[transfer.status] ?? STATUS.PENDING;
  return (
    <article className={`receipt ${s.tone}`} aria-live="polite">
      <div className="receipt-body">
        <p className="receipt-state">
          {transfer.status === 'PENDING' && <span className="pulse" aria-hidden="true" />}
          {s.label}
        </p>
        <p className="receipt-amount">{money(transfer.amount, transfer.currency)}</p>
        <dl>
          <div><dt>Từ</dt><dd>{from ?? shortId(transfer.sourceAccountId)}</dd></div>
          <div><dt>Đến</dt><dd>{to ?? shortId(transfer.destAccountId)}</dd></div>
          <div><dt>Thời gian</dt><dd>{dateTime(transfer.createdAt)}</dd></div>
          <div><dt>Mã giao dịch</dt><dd className="mono">{shortId(transfer.id)}</dd></div>
        </dl>
        {transfer.failureReason && <p className="receipt-reason">{reasonText(transfer.failureReason)}</p>}
      </div>
    </article>
  );
}

export function RequireRole({ role }: { role?: Role }) {
  const session = useSession();
  const location = useLocation();
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (role && session.customer.role !== role) return <Navigate to="/app" replace />;
  return <Outlet />;
}

/** [path, label, match the path exactly] */
type NavItem = [to: string, label: string, end?: boolean];

const USER_NAV: NavItem[] = [
  ['/app', 'Tổng quan', true],
  ['/app/accounts', 'Tài khoản'],
  ['/app/transfer', 'Chuyển tiền'],
  ['/app/transactions', 'Lịch sử giao dịch'],
  ['/app/notifications', 'Thông báo'],
  ['/app/profile', 'Hồ sơ'],
];
const ADMIN_NAV: NavItem[] = [
  ['/admin', 'Khách hàng', true],
  ['/admin/accounts', 'Tài khoản hệ thống'],
  ['/admin/transfers', 'Giao dịch hệ thống'],
];

export function AppShell() {
  const customer = useCustomer();
  const navigate = useNavigate();
  // Leave first: otherwise RequireRole remembers this page as `from` for whoever signs in next.
  const signOut = () => { navigate('/login', { replace: true }); logout(); };
  const links = (items: NavItem[]) => items.map(([to, label, end]) => (
    <NavLink key={to} to={to} end={end}>{label}</NavLink>
  ));
  return (
    <div className="shell">
      <aside className="sidebar">
        <Link to="/app" className="brand">Banking</Link>
        <nav aria-label="Ngân hàng của tôi">{links(USER_NAV)}</nav>
        {customer.role === 'ADMIN' && (
          <nav aria-label="Quản trị">
            <p className="nav-group">Quản trị</p>
            {links(ADMIN_NAV)}
          </nav>
        )}
        <div className="me">
          <p>{customer.fullName}</p>
          <p className="muted-dark">{customer.email}</p>
          <button type="button" className="ghost-dark small" onClick={signOut}>Đăng xuất</button>
        </div>
      </aside>
      <main className="content"><Outlet /></main>
    </div>
  );
}
