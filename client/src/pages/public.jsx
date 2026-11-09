import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { api, login } from '../gateway.js';
import { validationText } from '../format.js';
import { useSession } from '../hooks.js';
import { Receipt } from '../ui.jsx';

const SAMPLE = { amount: 2500000, currency: 'VND', sourceAccountId: '', destAccountId: '' };

/** The landing's one orchestrated moment: a sample receipt walks through the real saga once. */
function LiveReceipt() {
  const still = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  const [status, setStatus] = useState(still ? 'COMPLETED' : 'PENDING');
  const [run, setRun] = useState(0);
  useEffect(() => {
    if (still) return undefined;
    setStatus('PENDING');
    const t = setTimeout(() => setStatus('COMPLETED'), 2600);
    return () => clearTimeout(t);
  }, [run, still]);
  return (
    <div className="demo" aria-label="Biên lai mẫu">
      <Receipt transfer={{ ...SAMPLE, status }} from="VN03 8144 17" to="VN77 2009 53" />
      <div className="demo-foot">
        <p className="hint">Biên lai mẫu. Biên lai thật cập nhật theo từng bước như thế này.</p>
        {!still && status === 'COMPLETED' && (
          <button type="button" className="btn btn-ghost small" onClick={() => setRun((r) => r + 1)}>Xem lại</button>
        )}
      </div>
    </div>
  );
}

export function Landing() {
  const navigate = useNavigate();
  const start = (e) => {
    e.preventDefault();
    const email = new FormData(e.currentTarget).get('email').trim();
    navigate(email ? `/register?email=${encodeURIComponent(email)}` : '/register');
  };
  return (
    <>
      <section className="hero">
        <div className="hero-copy">
          <h1>Chuyển tiền, rồi thấy ngay kết quả.</h1>
          <p className="lead">Mỗi lệnh chuyển có một biên lai theo dõi trực tiếp, từ lúc bạn bấm gửi đến khi tiền vào tài khoản người nhận.</p>
          <form className="signup" onSubmit={start}>
            <label className="sr-only" htmlFor="hero-email">Email</label>
            <input id="hero-email" name="email" type="email" placeholder="Email của bạn" autoComplete="email" />
            <button type="submit" className="btn btn-primary">Mở tài khoản</button>
          </form>
          <p className="hint">Đã có tài khoản? <Link to="/login">Đăng nhập</Link></p>
        </div>
        <LiveReceipt />
      </section>

      <section className="promises" aria-labelledby="promises-title">
        <h2 id="promises-title">Ba điều hệ thống luôn giữ cho tiền của bạn.</h2>
        <div className="promise-cols">
          <div>
            <h3>Bấm gửi hai lần vẫn chỉ trừ một lần</h3>
            <p>Mỗi lệnh mang một mã riêng. Mạng chập chờn và bạn gửi lại, hệ thống trả về đúng lệnh cũ thay vì tạo lệnh mới.</p>
          </div>
          <div>
            <h3>Kiểm tra an toàn trước khi tiền đi</h3>
            <p>Lệnh có số tiền bất thường, hoặc quá nhiều lệnh trong một phút, sẽ bị chặn trước khi số dư thay đổi.</p>
          </div>
          <div>
            <h3>Đối tác lỗi, tiền tự hoàn</h3>
            <p>Nếu ngân hàng nhận không phản hồi, khoản tiền đã trừ được ghi đảo và trả lại tài khoản của bạn.</p>
          </div>
        </div>
      </section>

      <section className="closing">
        <h2>Mở tài khoản mất chưa tới một phút.</h2>
        <Link className="btn btn-frost" to="/register">Mở tài khoản</Link>
      </section>
    </>
  );
}

function useSubmit(action) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const onSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await action(Object.fromEntries(new FormData(e.currentTarget)));
    } catch (err) {
      setBusy(false); // first, so the button can never stay stuck on "Đang…"
      const known = { 401: 'Email hoặc mật khẩu không đúng.', 409: 'Email này đã được đăng ký. Hãy đăng nhập.', 423: 'Tài khoản đã bị khoá. Liên hệ ngân hàng để mở lại.' };
      setError(known[err.status] ?? validationText(err.data?.errors) ?? err.message);
    }
  };
  return { error, busy, onSubmit };
}

function Password({ name, autoComplete, label, hint }) {
  const [shown, setShown] = useState(false);
  return (
    <label>{label}
      <span className="field">
        <input type={shown ? 'text' : 'password'} name={name} required minLength={autoComplete === 'new-password' ? 8 : undefined}
          maxLength={100} autoComplete={autoComplete} />
        <button type="button" className="btn btn-ghost small" onClick={() => setShown((s) => !s)} aria-pressed={shown}
          aria-label={`${shown ? 'Ẩn' : 'Hiện'} ${label.toLowerCase()}`}>
          {shown ? 'Ẩn' : 'Hiện'}
        </button>
      </span>
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}

function AuthCard({ title, lead, children, footer }) {
  return (
    <div className="auth">
      <div className="object">
        <div className="stack" style={{ gap: 6 }}>
          <h1>{title}</h1>
          {lead && <p className="muted">{lead}</p>}
        </div>
        {children}
        <p className="hint">{footer}</p>
      </div>
    </div>
  );
}

const home = (customer) => (customer.role === 'ADMIN' ? '/admin' : '/app');

export function Login() {
  const session = useSession();
  const navigate = useNavigate();
  const from = useLocation().state?.from;
  const { error, busy, onSubmit } = useSubmit(async ({ email, password }) => {
    const customer = await login(email, password);
    navigate(from ?? home(customer), { replace: true });
  });
  if (session) return <Navigate to={home(session.customer)} replace />;
  return (
    <AuthCard title="Đăng nhập" footer={<>Chưa có tài khoản? <Link to="/register">Mở tài khoản</Link></>}>
      <form onSubmit={onSubmit} className="stack">
        <label>Email<span className="field"><input type="email" name="email" required autoComplete="username" autoFocus /></span></label>
        <Password name="password" label="Mật khẩu" autoComplete="current-password" />
        {error && <p className="alert bad" role="alert">{error}</p>}
        <button type="submit" className="btn btn-primary full" disabled={busy}>{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
      </form>
    </AuthCard>
  );
}

export function Register() {
  const session = useSession();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { error, busy, onSubmit } = useSubmit(async ({ fullName, email, password, confirm }) => {
    if (password !== confirm) throw new Error('Hai mật khẩu không khớp.');
    await api('POST', '/auth/register', { body: { fullName, email, password } });
    await login(email, password);
    // A new customer has no account yet; open one in VND so the dashboard is usable immediately.
    await api('POST', '/accounts', { body: { currency: 'VND' } }).catch(() => {});
    navigate('/app', { replace: true });
  });
  if (session) return <Navigate to={home(session.customer)} replace />;
  return (
    <AuthCard title="Mở tài khoản" lead="Tài khoản thanh toán VND đầu tiên được mở cùng lúc."
      footer={<>Đã có tài khoản? <Link to="/login">Đăng nhập</Link></>}>
      <form onSubmit={onSubmit} className="stack">
        <label>Họ và tên<span className="field"><input name="fullName" required maxLength={255} autoComplete="name" autoFocus /></span></label>
        <label>Email<span className="field"><input type="email" name="email" required autoComplete="email" defaultValue={params.get('email') ?? ''} /></span></label>
        <Password name="password" label="Mật khẩu" autoComplete="new-password" hint="Ít nhất 8 ký tự." />
        <Password name="confirm" label="Nhập lại mật khẩu" autoComplete="new-password" />
        {error && <p className="alert bad" role="alert">{error}</p>}
        <button type="submit" className="btn btn-primary full" disabled={busy}>{busy ? 'Đang tạo tài khoản…' : 'Mở tài khoản'}</button>
      </form>
    </AuthCard>
  );
}

export const NotFound = () => (
  <AuthCard title="Không tìm thấy trang" footer={<Link to="/">Về trang chủ</Link>}>
    <p className="muted">Đường dẫn này không tồn tại hoặc đã bị đổi.</p>
  </AuthCard>
);
