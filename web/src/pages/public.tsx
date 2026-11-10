import { useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError, api, errorMessage, login } from '../gateway';
import { Receipt, useSession } from '../components';
import type { ReceiptTransfer } from '../components';
import type { Account } from '../types';

const SAMPLE: ReceiptTransfer = {
  id: 'a41c09e2-6b3f-4c8e-9d21-5f7e3b0c8d14',
  amount: 2500000,
  currency: 'VND',
  status: 'COMPLETED',
  createdAt: '2026-11-03T09:24:00Z',
};

export function Landing() {
  const session = useSession();
  return (
    <div className="public">
      <header className="public-bar">
        <span className="brand">Banking</span>
        <nav>
          {session ? <Link className="button" to="/app">Vào tài khoản</Link> : (
            <>
              <Link className="ghost-dark button" to="/login">Đăng nhập</Link>
              <Link className="button" to="/register">Mở tài khoản</Link>
            </>
          )}
        </nav>
      </header>
      <section className="hero">
        <div className="hero-copy">
          <h1>Chuyển tiền, rồi thấy ngay kết quả.</h1>
          <p className="lead">
            Mỗi lệnh chuyển có một biên lai theo dõi trực tiếp, từ lúc gửi đến khi tiền vào tài khoản người nhận.
          </p>
          <div className="row">
            <Link className="button" to="/register">Mở tài khoản miễn phí</Link>
            <Link className="ghost-dark button" to="/login">Tôi đã có tài khoản</Link>
          </div>
        </div>
        <div className="hero-receipt" aria-label="Biên lai mẫu">
          <Receipt transfer={SAMPLE} from="VN0381 •••• 4417" to="VN7720 •••• 0953" />
        </div>
      </section>
      <section className="facts">
        <div>
          <h2>Bấm gửi hai lần vẫn chỉ trừ một lần</h2>
          <p>Mỗi lệnh mang một mã riêng. Mạng chập chờn và bạn gửi lại, hệ thống trả về đúng lệnh cũ thay vì tạo lệnh mới.</p>
        </div>
        <div>
          <h2>Kiểm tra an toàn trước khi tiền đi</h2>
          <p>Lệnh có số tiền bất thường, hoặc quá nhiều lệnh trong một phút, sẽ bị chặn trước khi số dư thay đổi.</p>
        </div>
        <div>
          <h2>Đối tác lỗi, tiền tự hoàn</h2>
          <p>Nếu ngân hàng nhận không phản hồi, khoản tiền đã trừ được ghi đảo và trả lại tài khoản của bạn.</p>
        </div>
      </section>
      <footer className="public-foot">Dự án học tập về hệ thống ngân hàng phân tán. Không xử lý tiền thật.</footer>
    </div>
  );
}

function AuthFrame({ title, children, footer }: { title: string; children: ReactNode; footer: ReactNode }) {
  return (
    <div className="auth-page">
      <Link to="/" className="brand">Banking</Link>
      <div className="auth-card">
        <h1>{title}</h1>
        {children}
      </div>
      <p className="auth-foot">{footer}</p>
    </div>
  );
}

/** Form field values by input name (these forms have text inputs only). */
type Fields = Record<string, string>;

const KNOWN_ERRORS: Record<number, string> = {
  401: 'Email hoặc mật khẩu không đúng.',
  409: 'Email này đã được đăng ký. Hãy đăng nhập.',
  423: 'Tài khoản đã bị khoá. Liên hệ ngân hàng để mở lại.',
};

function useSubmit(action: (fields: Fields) => Promise<void>) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const onSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const fields: Fields = {};
      new FormData(e.currentTarget).forEach((value, name) => { fields[name] = String(value); });
      await action(fields);
    } catch (err) {
      setError((err instanceof ApiError && KNOWN_ERRORS[err.status]) || errorMessage(err));
      setBusy(false);
    }
  };
  return { error, busy, onSubmit };
}

export function Login() {
  const session = useSession();
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from;
  const { error, busy, onSubmit } = useSubmit(async ({ email, password }) => {
    const customer = await login(email, password);
    navigate(from ?? (customer.role === 'ADMIN' ? '/admin' : '/app'), { replace: true });
  });
  if (session) return <Navigate to="/app" replace />;
  return (
    <AuthFrame title="Đăng nhập" footer={<>Chưa có tài khoản? <Link to="/register">Mở tài khoản</Link></>}>
      <form onSubmit={onSubmit} className="stack">
        <label>Email<input type="email" name="email" required autoComplete="username" autoFocus /></label>
        <label>Mật khẩu<input type="password" name="password" required autoComplete="current-password" /></label>
        {error && <p className="alert bad" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
      </form>
    </AuthFrame>
  );
}

export function Register() {
  const session = useSession();
  const navigate = useNavigate();
  const { error, busy, onSubmit } = useSubmit(async ({ fullName, email, password, confirm }) => {
    if (password !== confirm) throw new Error('Hai mật khẩu không khớp.');
    await api('POST', '/auth/register', { body: { fullName, email, password } });
    await login(email, password);
    // A new customer has no account yet; open one in VND so the dashboard is usable immediately.
    await api<Account>('POST', '/accounts', { body: { currency: 'VND' } }).catch(() => undefined);
    navigate('/app', { replace: true });
  });
  if (session) return <Navigate to="/app" replace />;
  return (
    <AuthFrame title="Mở tài khoản" footer={<>Đã có tài khoản? <Link to="/login">Đăng nhập</Link></>}>
      <form onSubmit={onSubmit} className="stack">
        <label>Họ và tên<input name="fullName" required maxLength={255} autoComplete="name" autoFocus /></label>
        <label>Email<input type="email" name="email" required autoComplete="email" /></label>
        <label>Mật khẩu<input type="password" name="password" required minLength={8} maxLength={100} autoComplete="new-password" />
          <span className="hint">Ít nhất 8 ký tự.</span></label>
        <label>Nhập lại mật khẩu<input type="password" name="confirm" required minLength={8} maxLength={100} autoComplete="new-password" /></label>
        {error && <p className="alert bad" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>{busy ? 'Đang tạo tài khoản…' : 'Mở tài khoản'}</button>
        <p className="hint">Tài khoản thanh toán VND đầu tiên được mở cùng lúc.</p>
      </form>
    </AuthFrame>
  );
}

export const NotFound = () => (
  <AuthFrame title="Không tìm thấy trang" footer={<Link to="/">Về trang chủ</Link>}>
    <p>Đường dẫn này không tồn tại hoặc đã bị đổi.</p>
  </AuthFrame>
);
