import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { getSession, logout, onSessionChange } from './gateway.js';
import { STATUS, dateTime, money, reasonText, shortId } from './format.js';

export const useSession = () => useSyncExternalStore(onSessionChange, getSession);

// Loads data on mount and whenever deps change; `reload` re-runs it without a spinner flash.
export function useLoad(fn, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(fn, deps);
  const reload = useCallback(async () => {
    try {
      const data = await run();
      setState({ data, error: null, loading: false });
    } catch (error) {
      setState((s) => ({ data: s.data, error, loading: false }));
    }
  }, [run]);
  useEffect(() => {
    let live = true;
    setState((s) => ({ ...s, loading: true }));
    run().then(
      (data) => live && setState({ data, error: null, loading: false }),
      (error) => live && setState({ data: null, error, loading: false }),
    );
    return () => { live = false; };
  }, [run]);
  return { ...state, reload };
}

export function Loaded({ state, children, empty, isEmpty = (d) => Array.isArray(d) && d.length === 0 }) {
  if (state.loading && !state.data) return <p className="muted">Đang tải…</p>;
  if (state.error && !state.data) return <p className="alert bad" role="alert">{state.error.message}</p>;
  if (empty && isEmpty(state.data)) return <div className="empty">{empty}</div>;
  return children(state.data);
}

export const Money = ({ value, currency, sign }) => (
  <span className={`money ${sign === '+' ? 'ok' : ''}`}>{sign}{money(value, currency)}</span>
);

export function StatusBadge({ status }) {
  const s = STATUS[status] ?? { label: status, tone: '' };
  return <span className={`badge ${s.tone}`}>{s.label}</span>;
}

export function CopyButton({ text, label = 'Sao chép' }) {
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

export const PageHead = ({ title, children }) => (
  <header className="page-head">
    <h1>{title}</h1>
    {children && <div className="actions">{children}</div>}
  </header>
);

/** The signature element: a paper receipt for one transfer. */
export function Receipt({ transfer, from, to }) {
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

export function RequireRole({ role }) {
  const session = useSession();
  const location = useLocation();
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (role && session.customer.role !== role) return <Navigate to="/app" replace />;
  return <Outlet />;
}

const USER_NAV = [
  ['/app', 'Tổng quan', true],
  ['/app/accounts', 'Tài khoản'],
  ['/app/transfer', 'Chuyển tiền'],
  ['/app/transactions', 'Lịch sử giao dịch'],
  ['/app/notifications', 'Thông báo'],
  ['/app/profile', 'Hồ sơ'],
];
const ADMIN_NAV = [
  ['/admin', 'Khách hàng', true],
  ['/admin/accounts', 'Tài khoản hệ thống'],
  ['/admin/transfers', 'Giao dịch hệ thống'],
];

export function AppShell() {
  const { customer } = useSession();
  const navigate = useNavigate();
  // Leave first: otherwise RequireRole remembers this page as `from` for whoever signs in next.
  const signOut = () => { navigate('/login', { replace: true }); logout(); };
  const links = (items) => items.map(([to, label, end]) => (
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
