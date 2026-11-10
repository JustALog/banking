import { useEffect, useState } from 'react';
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { logout } from './gateway.js';
import { useSession } from './hooks.js';

const Brand = ({ to }) => (
  <Link to={to} className="brand"><span className="brand-mark" aria-hidden="true" />Banking</Link>
);

export function SiteFooter() {
  return (
    <footer className="footer">
      <div className="footer-inner">
        <div className="footer-cols">
          <Brand to="/" />
          <div>
            <h3>Sản phẩm</h3>
            <Link to="/app/accounts">Tài khoản</Link>
            <Link to="/app/transfer">Chuyển tiền</Link>
          </div>
          <div>
            <h3>Tài khoản</h3>
            <Link to="/login">Đăng nhập</Link>
            <Link to="/register">Mở tài khoản</Link>
          </div>
        </div>
        <p className="legal">
          Dự án học tập về hệ thống ngân hàng phân tán. Không xử lý tiền thật. Mọi số dư và giao dịch chỉ tồn tại trong môi trường thử nghiệm.
        </p>
      </div>
    </footer>
  );
}

export function PublicFrame() {
  const session = useSession();
  const { pathname } = useLocation();
  return (
    <>
      <header className="nav">
        <Brand to="/" />
        <div className="nav-end">
          {/* Never link to the page you are on: the form below is already the action. */}
          {session ? <Link className="btn btn-primary" to={session.customer.role === 'ADMIN' ? '/admin' : '/app'}>Vào tài khoản</Link> : (
            <>
              {pathname !== '/login' && <Link className="btn btn-ghost" to="/login">Đăng nhập</Link>}
              {pathname !== '/register' && <Link className={`btn ${pathname === '/login' ? 'btn-frost' : 'btn-primary'}`} to="/register">Mở tài khoản</Link>}
            </>
          )}
        </div>
      </header>
      <main><Outlet /></main>
      <SiteFooter />
    </>
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
  ['/app/transactions', 'Lịch sử'],
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
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const inAdmin = pathname.startsWith('/admin');
  useEffect(() => setOpen(false), [pathname]);
  // Leave first: otherwise RequireRole remembers this page as `from` for whoever signs in next.
  const signOut = () => { navigate('/login', { replace: true }); logout(); };

  return (
    <>
      <header className="nav">
        <Brand to={inAdmin ? '/admin' : '/app'} />
        <nav id="main-nav" className={`nav-links ${open ? 'open' : ''}`} aria-label={inAdmin ? 'Quản trị' : 'Ngân hàng của tôi'}>
          {(inAdmin ? ADMIN_NAV : USER_NAV).map(([to, label, end]) => (
            <NavLink key={to} to={to} end={end} className="btn btn-ghost">{label}</NavLink>
          ))}
          {customer.role === 'ADMIN' && (
            <Link className="btn btn-frost" to={inAdmin ? '/app' : '/admin'}>{inAdmin ? 'Ngân hàng của tôi' : 'Quản trị'}</Link>
          )}
        </nav>
        <div className="nav-end">
          <p className="me"><span>{customer.fullName}</span><span>{customer.email}</span></p>
          <button type="button" className="btn btn-frost small" onClick={signOut}>Đăng xuất</button>
          <button type="button" className="btn btn-icon nav-menu" aria-expanded={open} aria-controls="main-nav"
            aria-label={open ? 'Đóng menu' : 'Mở menu'} onClick={() => setOpen((o) => !o)}>
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path d={open ? 'M3 3l10 10M13 3L3 13' : 'M2 4h12M2 8h12M2 12h12'} stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </div>
      </header>
      <main className="content"><Outlet /></main>
    </>
  );
}
