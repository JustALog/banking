import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { api } from '../gateway.js';
import { CopyButton, Loaded, Money, PageHead, Receipt, StatusBadge, useLoad, useSession } from '../components.jsx';
import { STATUS, UUID_RE, date, dateTime, money, reasonText, shortId, totalsByCurrency } from '../format.js';

const get = (path) => () => api('GET', path).then((r) => r.data);
const useAccounts = () => useLoad(get('/accounts'));
const accountLabel = (accounts, id) => accounts?.find((a) => a.id === id)?.accountNo;

/* ---------- Dashboard ---------- */

export function Dashboard() {
  const { customer } = useSession();
  const accounts = useAccounts();
  const transfers = useLoad(get('/transfers'));
  const notes = useLoad(get('/notifications'));
  return (
    <>
      <PageHead title={`Xin chào, ${customer.fullName}`}>
        <Link className="button" to="/app/transfer">Chuyển tiền</Link>
      </PageHead>

      <Loaded state={accounts} empty={<NoAccount reload={accounts.reload} />}>
        {(list) => (
          <>
            <section className="totals" aria-label="Tổng số dư">
              {totalsByCurrency(list).map(([cur, sum]) => (
                <div key={cur}>
                  <p className="muted">Tổng số dư {cur}</p>
                  <p className="big-money">{money(sum, cur)}</p>
                </div>
              ))}
            </section>
            <div className="cards">
              {list.map((a) => <AccountCard key={a.id} account={a} />)}
            </div>
          </>
        )}
      </Loaded>

      <div className="split">
        <section className="panel">
          <div className="panel-head"><h2>Giao dịch gần đây</h2><Link to="/app/transactions">Xem tất cả</Link></div>
          <Loaded state={transfers} empty={<p>Chưa có giao dịch. <Link to="/app/transfer">Chuyển khoản đầu tiên</Link>.</p>}>
            {(list) => <TransferList transfers={list.slice(0, 5)} accounts={accounts.data} />}
          </Loaded>
        </section>
        <section className="panel">
          <div className="panel-head"><h2>Thông báo</h2><Link to="/app/notifications">Xem tất cả</Link></div>
          <Loaded state={notes} empty={<p className="muted">Bạn sẽ nhận thông báo khi một giao dịch kết thúc.</p>}>
            {(list) => (
              <ul className="notes">
                {list.slice(0, 4).map((n) => <li key={n.id}><strong>{n.title}</strong><span className="muted">{dateTime(n.createdAt)}</span></li>)}
              </ul>
            )}
          </Loaded>
        </section>
      </div>
    </>
  );
}

function NoAccount({ reload }) {
  return (
    <div>
      <p>Bạn chưa có tài khoản thanh toán nào.</p>
      <OpenAccount onOpened={reload} />
    </div>
  );
}

function AccountCard({ account }) {
  return (
    <Link to={`/app/accounts/${account.id}`} className="account-card">
      <span className="muted-dark">Tài khoản thanh toán {account.currency}</span>
      <span className="card-no">{account.accountNo.replace(/(.{4})/g, '$1 ').trim()}</span>
      <span className="card-balance">{money(account.balance, account.currency)}</span>
    </Link>
  );
}

function TransferList({ transfers, accounts }) {
  return (
    <ul className="tx-list">
      {transfers.map((t) => (
        <li key={t.id}>
          <Link to={`/app/transactions/${t.id}`}>
            <span>
              <span className="tx-to">Đến {accountLabel(accounts, t.destAccountId) ?? shortId(t.destAccountId)}</span>
              <span className="muted">{dateTime(t.createdAt)}</span>
            </span>
            <span className="tx-right">
              <Money value={-t.amount} currency={t.currency} />
              <StatusBadge status={t.status} />
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

/* ---------- Accounts ---------- */

function OpenAccount({ onOpened }) {
  const [currency, setCurrency] = useState('VND');
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const { data } = await api('POST', '/accounts', { body: { currency } });
      setMsg({ tone: 'ok', text: `Đã mở tài khoản ${data.accountNo}.` });
      onOpened?.();
    } catch (err) {
      setMsg({ tone: 'bad', text: err.message });
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="row" onSubmit={submit}>
      <label className="inline">Loại tiền
        <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
          <option value="VND">VND</option><option value="USD">USD</option><option value="EUR">EUR</option>
        </select>
      </label>
      <button type="submit" disabled={busy}>Mở tài khoản mới</button>
      {msg && <p className={`alert ${msg.tone}`} role="status">{msg.text}</p>}
    </form>
  );
}

export function Accounts() {
  const accounts = useAccounts();
  return (
    <>
      <PageHead title="Tài khoản" />
      <section className="panel"><OpenAccount onOpened={accounts.reload} /></section>
      <Loaded state={accounts} empty={<p>Mở tài khoản đầu tiên ở trên để bắt đầu.</p>}>
        {(list) => <div className="cards">{list.map((a) => <AccountCard key={a.id} account={a} />)}</div>}
      </Loaded>
    </>
  );
}

export function AccountDetail() {
  const { id } = useParams();
  const account = useLoad(get(`/accounts/${id}`), [id]);
  const entries = useLoad(get(`/accounts/${id}/entries`), [id]);
  return (
    <Loaded state={account}>
      {(a) => (
        <>
          <PageHead title={`Tài khoản ${a.accountNo}`}>
            <Link className="button" to={`/app/transfer?from=${a.id}`}>Chuyển tiền từ tài khoản này</Link>
          </PageHead>
          <section className="totals">
            <div>
              <p className="muted">Số dư khả dụng</p>
              <p className="big-money">{money(a.balance, a.currency)}</p>
              <p className="muted">Mở ngày {date(a.createdAt)}</p>
            </div>
            <div className="receive">
              <p className="muted">Mã nhận tiền. Gửi mã này cho người muốn chuyển tiền cho bạn.</p>
              <p className="mono break">{a.id}</p>
              <CopyButton text={a.id} label="Sao chép mã nhận tiền" />
            </div>
          </section>
          <section className="panel">
            <h2>Sao kê</h2>
            <Loaded state={entries} empty={<p className="muted">Chưa có biến động số dư.</p>}>
              {(list) => (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th scope="col">Thời gian</th><th scope="col">Nội dung</th><th scope="col">Mã tham chiếu</th><th scope="col" className="num">Số tiền</th></tr></thead>
                    <tbody>
                      {list.map((e) => (
                        <tr key={e.id}>
                          <td>{dateTime(e.createdAt)}</td>
                          <td>{e.direction === 'CREDIT' ? 'Tiền vào' : 'Tiền ra'}</td>
                          <td className="mono">{shortId(e.transferId)}</td>
                          <td className="num"><Money value={e.amount} currency={a.currency} sign={e.direction === 'CREDIT' ? '+' : '−'} /></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Loaded>
          </section>
        </>
      )}
    </Loaded>
  );
}

/* ---------- Transfer ---------- */

export function Transfer() {
  const accounts = useAccounts();
  return (
    <>
      <PageHead title="Chuyển tiền" />
      <Loaded state={accounts} empty={<p>Bạn cần một tài khoản để chuyển tiền. <Link to="/app/accounts">Mở tài khoản</Link>.</p>}>
        {(list) => <TransferForm accounts={list} />}
      </Loaded>
    </>
  );
}

function TransferForm({ accounts }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [source, setSource] = useState(accounts.some((a) => a.id === params.get('from')) ? params.get('from') : accounts[0].id);
  const [mode, setMode] = useState(accounts.length > 1 ? 'own' : 'other');
  const [own, setOwn] = useState('');
  const [other, setOther] = useState('');
  const [amount, setAmount] = useState('');
  const [review, setReview] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // One key per intended transfer: a retry after a network error reuses it, so money moves at most once.
  const key = useRef(crypto.randomUUID());

  const from = accounts.find((a) => a.id === source);
  const targets = accounts.filter((a) => a.id !== source && a.currency === from.currency);
  const ownTarget = targets.find((a) => a.id === own) ?? targets[0];
  const dest = mode === 'own' ? (ownTarget?.id ?? '') : other.trim();
  const value = Number(amount);

  const problem = useMemo(() => {
    if (!dest) return mode === 'own' ? 'Bạn chưa có tài khoản khác cùng loại tiền.' : 'Nhập mã nhận tiền của người nhận.';
    if (!UUID_RE.test(dest)) return 'Mã nhận tiền không đúng định dạng.';
    if (dest === source) return 'Tài khoản nhận phải khác tài khoản nguồn.';
    if (!Number.isSafeInteger(value) || value <= 0) return 'Nhập số tiền là số nguyên dương.';
    if (value > from.balance) return `Số tiền vượt số dư (${money(from.balance, from.currency)}).`;
    return '';
  }, [dest, mode, source, value, from]);

  const edit = (setter) => (e) => { setter(e.target.value); setReview(false); setError(''); key.current = crypto.randomUUID(); };

  const send = async () => {
    setBusy(true);
    setError('');
    try {
      const { data } = await api('POST', '/transfers', {
        body: { sourceAccountId: source, destAccountId: dest, amount: value, currency: from.currency },
        headers: { 'Idempotency-Key': key.current },
      });
      navigate(`/app/transactions/${data.id}`);
    } catch (err) {
      setError(err.status === 422 ? 'Không tìm thấy tài khoản nhận, hoặc tài khoản không hợp lệ.' : err.message);
      setBusy(false);
    }
  };

  if (review) {
    const toLabel = accountLabel(accounts, dest) ?? shortId(dest);
    return (
      <section className="panel narrow">
        <h2>Xác nhận chuyển tiền</h2>
        <dl className="summary">
          <div><dt>Từ tài khoản</dt><dd>{from.accountNo}</dd></div>
          <div><dt>Đến</dt><dd>{toLabel}</dd></div>
          <div><dt>Số tiền</dt><dd className="big-money">{money(value, from.currency)}</dd></div>
          <div><dt>Số dư sau giao dịch</dt><dd>{money(from.balance - value, from.currency)}</dd></div>
        </dl>
        {error && <p className="alert bad" role="alert">{error}</p>}
        <div className="row">
          <button type="button" onClick={send} disabled={busy}>{busy ? 'Đang gửi…' : 'Xác nhận chuyển'}</button>
          <button type="button" className="ghost" onClick={() => setReview(false)} disabled={busy}>Sửa lại</button>
        </div>
      </section>
    );
  }

  return (
    <form className="panel narrow stack" onSubmit={(e) => { e.preventDefault(); if (!problem) setReview(true); }}>
      <label>Từ tài khoản
        <select value={source} onChange={edit(setSource)}>
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.accountNo} ({money(a.balance, a.currency)})</option>)}
        </select>
      </label>
      <fieldset>
        <legend>Chuyển đến</legend>
        <div className="segmented">
          <label><input type="radio" name="mode" value="own" checked={mode === 'own'} onChange={edit(setMode)} />Tài khoản của tôi</label>
          <label><input type="radio" name="mode" value="other" checked={mode === 'other'} onChange={edit(setMode)} />Người khác</label>
        </div>
        {mode === 'own' ? (
          targets.length ? (
            <select aria-label="Tài khoản nhận" value={ownTarget.id} onChange={edit(setOwn)}>
              {targets.map((a) => <option key={a.id} value={a.id}>{a.accountNo} ({money(a.balance, a.currency)})</option>)}
            </select>
          ) : <p className="hint">Bạn chưa có tài khoản {from.currency} nào khác. <Link to="/app/accounts">Mở thêm tài khoản</Link>.</p>
        ) : (
          <label>Mã nhận tiền của người nhận
            <input value={other} onChange={edit(setOther)} placeholder="vd. 3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b" spellCheck={false} autoComplete="off" />
            <span className="hint">Người nhận lấy mã này ở trang chi tiết tài khoản của họ.</span>
          </label>
        )}
      </fieldset>
      <label>Số tiền ({from.currency})
        <input type="number" inputMode="numeric" min={1} step={1} value={amount} onChange={edit(setAmount)} required />
        {value > 0 && <span className="hint">{money(value, from.currency)}</span>}
      </label>
      {amount && problem && <p className="alert bad" role="alert">{problem}</p>}
      <button type="submit" disabled={!!problem}>Tiếp tục</button>
    </form>
  );
}

/* ---------- Transactions ---------- */

const FILTERS = [['', 'Tất cả'], ...Object.entries(STATUS).map(([k, v]) => [k, v.label])];

export function Transactions() {
  const transfers = useLoad(get('/transfers'));
  const accounts = useAccounts();
  const [filter, setFilter] = useState('');
  return (
    <>
      <PageHead title="Lịch sử giao dịch">
        <button type="button" className="ghost" onClick={transfers.reload}>Làm mới</button>
      </PageHead>
      <div className="segmented" role="group" aria-label="Lọc theo trạng thái">
        {FILTERS.map(([k, label]) => (
          <button key={k} type="button" aria-pressed={filter === k} onClick={() => setFilter(k)}>{label}</button>
        ))}
      </div>
      <section className="panel">
        <Loaded state={transfers} empty={<p>Chưa có giao dịch. <Link to="/app/transfer">Chuyển khoản đầu tiên</Link>.</p>}>
          {(list) => {
            const shown = list.filter((t) => !filter || t.status === filter);
            return shown.length ? <TransferList transfers={shown} accounts={accounts.data} /> : <p className="muted">Không có giao dịch nào ở trạng thái này.</p>;
          }}
        </Loaded>
      </section>
    </>
  );
}

const POLL_MS = 1500;
const POLL_LIMIT_MS = 60000;

export function TransactionDetail() {
  const { id } = useParams();
  const transfer = useLoad(get(`/transfers/${id}`), [id]);
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
      <Loaded state={transfer}>
        {(t) => (
          <div className="detail">
            <Receipt transfer={t} from={accountLabel(accounts.data, t.sourceAccountId)} to={accountLabel(accounts.data, t.destAccountId)} />
            <div className="stack">
              {pending && !stalled && <p className="muted">Đang chờ kiểm tra an toàn và ghi sổ. Trang tự cập nhật.</p>}
              {pending && stalled && <p className="alert wait">Giao dịch vẫn đang xử lý lâu hơn bình thường. Bạn có thể rời trang; kết quả sẽ có trong Thông báo.</p>}
              {t.status === 'FAILED' && <p className="alert bad">{reasonText(t.failureReason)}</p>}
              {t.status === 'COMPLETED' && <p className="alert ok">Tiền đã vào tài khoản người nhận.</p>}
              <div className="row">
                <Link className="button" to={`/app/transfer?from=${t.sourceAccountId}`}>Chuyển tiền mới</Link>
                <Link className="ghost button" to="/app/transactions">Về lịch sử</Link>
              </div>
              <p className="hint">Mã giao dịch đầy đủ: <span className="mono break">{t.id}</span></p>
            </div>
          </div>
        )}
      </Loaded>
    </>
  );
}

/* ---------- Notifications & profile ---------- */

export function Notifications() {
  const notes = useLoad(get('/notifications'));
  return (
    <>
      <PageHead title="Thông báo">
        <button type="button" className="ghost" onClick={notes.reload}>Làm mới</button>
      </PageHead>
      <section className="panel">
        <Loaded state={notes} empty={<p className="muted">Chưa có thông báo. Bạn sẽ nhận thông báo khi một giao dịch kết thúc.</p>}>
          {(list) => (
            <ul className="note-list">
              {list.map((n) => (
                <li key={n.id}>
                  <div className="panel-head">
                    <strong>{n.title}</strong>
                    <span className="muted">{dateTime(n.createdAt)}</span>
                  </div>
                  <p>{n.content}</p>
                  <p className="hint">
                    Gửi qua {n.channel === 'SMS' ? 'SMS' : 'email'} tới {n.recipient}
                    {n.transferId && <>. <Link to={`/app/transactions/${n.transferId}`}>Xem giao dịch</Link></>}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </Loaded>
      </section>
    </>
  );
}

export function Profile() {
  const me = useLoad(get('/auth/me'));
  return (
    <>
      <PageHead title="Hồ sơ" />
      <Loaded state={me}>
        {(c) => (
          <section className="panel narrow">
            <dl className="summary">
              <div><dt>Họ và tên</dt><dd>{c.fullName}</dd></div>
              <div><dt>Email</dt><dd>{c.email}</dd></div>
              <div><dt>Loại khách hàng</dt><dd>{c.role === 'ADMIN' ? 'Quản trị viên' : 'Cá nhân'}</dd></div>
              <div><dt>Trạng thái</dt><dd>{c.status === 'ACTIVE' ? 'Đang hoạt động' : 'Đã khoá'}</dd></div>
              <div><dt>Khách hàng từ</dt><dd>{date(c.createdAt)}</dd></div>
            </dl>
          </section>
        )}
      </Loaded>
    </>
  );
}
