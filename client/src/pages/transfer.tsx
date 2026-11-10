import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { api, get } from '../gateway.ts';
import { formatAccountNo, money, parseAmount, transferProblem } from '../format.ts';
import { useAccounts, useLoad } from '../hooks.ts';
import { Amount, CopyButton, EmptyState, Loaded, PageHead, Receipt } from '../ui.tsx';
import type { Account, Transfer as TransferRecord } from '../types.ts';
import { ApiError } from '../gateway.ts';
import { accountLabel } from './user.tsx';

const STEPS = ['Thông tin', 'Xác nhận', 'Kết quả'];

function Steps({ current }: { current: number }) {
  return (
    <ol className="steps" aria-label="Các bước chuyển tiền">
      {STEPS.map((s, i) => (
        <li key={s} className={i < current ? 'done' : ''} aria-current={i === current ? 'step' : undefined}>{s}</li>
      ))}
    </ol>
  );
}

export function Transfer() {
  const accounts = useAccounts();
  return (
    <>
      <PageHead title="Chuyển tiền" />
      <Loaded state={accounts} empty={
        <EmptyState title="Bạn cần một tài khoản để chuyển tiền" action={<Link className="btn btn-primary" to="/app/accounts">Mở tài khoản</Link>} />
      }>
        {(list) => <TransferForm accounts={list} />}
      </Loaded>
    </>
  );
}

function TransferForm({ accounts }: { accounts: Account[] }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [source, setSource] = useState(accounts.find((a) => a.id === params.get('from'))?.id ?? accounts[0].id);
  const [mode, setMode] = useState<'own' | 'other'>(accounts.length > 1 ? 'own' : 'other');
  const [own, setOwn] = useState('');
  const [other, setOther] = useState('');
  const [amountText, setAmountText] = useState('');
  const [review, setReview] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // One key per intended transfer: a retry after a network error reuses it, so money moves at most once.
  const key = useRef(crypto.randomUUID());

  const from = accounts.find((a) => a.id === source)!;
  const targets = accounts.filter((a) => a.id !== source && a.currency === from.currency);
  const ownTarget = targets.find((a) => a.id === own) ?? targets[0];
  const dest = mode === 'own' ? (ownTarget?.id ?? '') : other.replace(/\s+/g, '').toUpperCase();
  const amount = parseAmount(amountText);
  const problem = transferProblem({ mode, from, dest, amount });

  const change = <T,>(setter: (v: T) => void) => (value: T) => { setter(value); setReview(false); setError(''); key.current = crypto.randomUUID(); };
  // The setter's own state type (string or a literal union) is what the DOM value is cast to.
  const onInput = (setter: (v: any) => void) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => change(setter)(e.target.value);
  const canPaste = typeof navigator !== 'undefined' && !!navigator.clipboard?.readText;
  const paste = async () => {
    try { change(setOther)((await navigator.clipboard.readText()).trim()); } catch { /* permission denied: type it instead */ }
  };

  // A second click lands before React re-renders the disabled button; the ref drops it.
  const sending = useRef(false);
  const send = async () => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setError('');
    try {
      const { data } = await api<TransferRecord>('POST', '/transfers', {
        body: { sourceAccountId: source, ...(mode === 'own' ? { destAccountId: dest } : { destAccountNo: dest }), amount, currency: from.currency },
        headers: { 'Idempotency-Key': key.current },
      });
      navigate(`/app/transactions/${data.id}`, { state: { fromTransfer: true } });
    } catch (e) {
      const err = e as ApiError;
      setError(err.message);
      sending.current = false;
      setBusy(false);
    }
  };

  if (review) {
    return (
      <section className="object narrow stack">
        <Steps current={1} />
        <div className="stack" style={{ gap: 8 }}>
          <h2>Bạn sắp chuyển</h2>
          <Amount value={amount!} currency={from.currency} size="lg" />
        </div>
        <dl className="summary">
          <div><dt>Từ tài khoản</dt><dd className="acct">{formatAccountNo(from.accountNo)}</dd></div>
          <div><dt>Đến</dt><dd className="acct break">{mode === 'own' ? accountLabel(accounts, dest) ?? dest : formatAccountNo(dest)}</dd></div>
          <div><dt>Số dư sau giao dịch</dt><dd><Amount value={from.balance - amount!} currency={from.currency} /></dd></div>
        </dl>
        {error && <p className="alert bad" role="alert">{error}</p>}
        <div className="row">
          <button type="button" className="btn btn-primary" onClick={send} disabled={busy}>{busy ? 'Đang gửi…' : 'Xác nhận chuyển'}</button>
          <button type="button" className="btn btn-frost" onClick={() => setReview(false)} disabled={busy}>Sửa lại</button>
        </div>
      </section>
    );
  }

  return (
    <form className="object narrow stack" onSubmit={(e) => { e.preventDefault(); if (!problem) setReview(true); }}>
      <Steps current={0} />
      <fieldset>
        <legend>Từ tài khoản</legend>
        <div className="choices">
          {accounts.map((a) => (
            <label key={a.id} className="choice">
              <input type="radio" name="source" value={a.id} checked={source === a.id} onChange={onInput(setSource)} />
              <span className="acct">{formatAccountNo(a.accountNo)}</span>
              <Amount value={a.balance} currency={a.currency} />
            </label>
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend>Chuyển đến</legend>
        <div className="segmented">
          <label><input type="radio" name="mode" value="own" checked={mode === 'own'} onChange={onInput(setMode)} />Tài khoản của tôi</label>
          <label><input type="radio" name="mode" value="other" checked={mode === 'other'} onChange={onInput(setMode)} />Người khác</label>
        </div>
        {mode === 'own' ? (
          targets.length ? (
            <span className="field">
              <select aria-label="Tài khoản nhận" value={ownTarget.id} onChange={onInput(setOwn)}>
                {targets.map((a) => <option key={a.id} value={a.id}>{formatAccountNo(a.accountNo)} (số dư {money(a.balance, a.currency)})</option>)}
              </select>
            </span>
          ) : <p className="hint">Bạn chưa có tài khoản {from.currency} nào khác. <Link to="/app/accounts">Mở thêm tài khoản</Link>.</p>
        ) : (
          <label>Số tài khoản người nhận
            <span className="field">
              <input value={other} onChange={onInput(setOther)} placeholder="vd. VN0123456789" spellCheck={false} autoComplete="off" />
              {canPaste && <button type="button" className="btn btn-ghost small" onClick={paste}>Dán</button>}
            </span>
            <span className="hint">Người nhận xem số tài khoản ở trang chi tiết tài khoản của họ.</span>
          </label>
        )}
      </fieldset>

      <label>Số tiền
        <span className={`field big ${amountText && amount == null ? 'invalid' : ''}`}>
          <input inputMode="numeric" autoComplete="off" placeholder="0" value={amountText} onChange={onInput(setAmountText)} />
          <span className="unit">{from.currency}</span>
        </span>
        <span className="hint">{amount != null && amount > 0 ? money(amount, from.currency) : 'Số nguyên, có thể nhập 2.500.000'}</span>
      </label>

      {amountText && problem && <p className="alert bad" role="alert">{problem}</p>}
      <button type="submit" className="btn btn-primary full" disabled={!!problem}>Tiếp tục</button>
    </form>
  );
}

const POLL_MS = 1500;
const POLL_LIMIT_MS = 60000;

export function TransactionDetail() {
  const { id } = useParams();
  const fromTransfer = useLocation().state?.fromTransfer;
  const transfer = useLoad(get<TransferRecord>(`/transfers/${id}`), [id]);
  const accounts = useAccounts();
  const [stalled, setStalled] = useState(false);
  const pending = transfer.data?.status === 'PENDING';
  const { reload } = transfer;

  // The saga finishes asynchronously over Kafka, so the receipt polls until it settles.
  useEffect(() => {
    if (!pending) return undefined;
    const started = Date.now();
    const timer = setInterval(() => {
      if (Date.now() - started > POLL_LIMIT_MS) { setStalled(true); clearInterval(timer); return; }
      reload();
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [pending, reload]);

  return (
    <>
      <PageHead title="Chi tiết giao dịch" />
      {fromTransfer && <Steps current={2} />}
      <Loaded state={transfer}>
        {(t) => (
          <div className="detail">
            <Receipt transfer={t} from={accountLabel(accounts.data, t.sourceAccountId, t.sourceAccountNo)} to={accountLabel(accounts.data, t.destAccountId, t.destAccountNo)} />
            <div className="stack">
              {pending && !stalled && <p className="alert" role="status">Đang chờ kiểm tra an toàn và ghi sổ. Trang tự cập nhật.</p>}
              {pending && stalled && <p className="alert">Giao dịch vẫn đang xử lý lâu hơn bình thường. Bạn có thể rời trang; kết quả sẽ có trong Thông báo.</p>}
              {t.status === 'FAILED' && !t.failureReason && <p className="alert bad">Giao dịch không thành công.</p>}
              {t.status === 'COMPLETED' && <p className="alert ok">Tiền đã vào tài khoản người nhận.</p>}
              <div className="row">
                <Link className="btn btn-primary" to={`/app/transfer?from=${t.sourceAccountId}`}>Chuyển tiền mới</Link>
                <Link className="btn btn-frost" to="/app/transactions">Về lịch sử</Link>
              </div>
              <div className="block" style={{ gap: 8 }}>
                <p className="hint">Mã giao dịch đầy đủ</p>
                <p className="acct break">{t.id}</p>
                <div><CopyButton text={t.id} /></div>
              </div>
            </div>
          </div>
        )}
      </Loaded>
    </>
  );
}
