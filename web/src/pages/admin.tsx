import { useState } from 'react';
import type { FormEvent } from 'react';
import { api, errorMessage } from '../gateway';
import { Loaded, Money, PageHead, StatusBadge, useCustomer, useLoad } from '../components';
import type { Flash } from '../components';
import { STATUS_FILTERS, date, dateTime, money, reasonText, shortId } from '../format';
import type { Account, Customer, Transfer, TransferStatus } from '../types';

// Every /admin API is ROLE_ADMIN-only on the server; these pages are hidden from users, not trusted to protect.
const get = <T,>(path: string) => () => api<T>('GET', path).then((r) => r.data);

function Search({ value, onChange, label }: { value: string; onChange: (value: string) => void; label: string }) {
  return <input type="search" className="search" placeholder={label} aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} />;
}

const LIMIT_NOTE = <p className="hint">Hiển thị tối đa 200 bản ghi mới nhất.</p>;

export function AdminCustomers() {
  const me = useCustomer();
  const customers = useLoad(get<Customer[]>('/auth/admin/customers'));
  const [q, setQ] = useState('');
  const [msg, setMsg] = useState<Flash | null>(null);

  const toggle = async (c: Customer) => {
    const locking = c.status === 'ACTIVE';
    if (locking && !window.confirm(`Khoá ${c.email}? Khách hàng sẽ không đăng nhập được nữa.`)) return;
    try {
      await api<Customer>('POST', `/auth/admin/customers/${c.id}/${locking ? 'lock' : 'unlock'}`);
      setMsg({ tone: 'ok', text: `${locking ? 'Đã khoá' : 'Đã mở khoá'} ${c.email}.` });
      customers.reload();
    } catch (err) {
      setMsg({ tone: 'bad', text: errorMessage(err) });
    }
  };

  return (
    <>
      <PageHead title="Khách hàng"><Search value={q} onChange={setQ} label="Tìm tên, email" /></PageHead>
      {msg && <p className={`alert ${msg.tone}`} role="status">{msg.text}</p>}
      <section className="panel">
        <Loaded state={customers} empty={<p className="muted">Chưa có khách hàng.</p>}>
          {(list) => {
            const needle = q.trim().toLowerCase();
            const shown = list.filter((c) => !needle || c.email.includes(needle) || c.fullName.toLowerCase().includes(needle));
            return (
              <div className="table-wrap">
                <table>
                  <thead><tr><th scope="col">Khách hàng</th><th scope="col">Loại</th><th scope="col">Trạng thái</th><th scope="col">Ngày tham gia</th><th scope="col"><span className="sr-only">Thao tác</span></th></tr></thead>
                  <tbody>
                    {shown.map((c) => (
                      <tr key={c.id}>
                        <td><strong>{c.fullName}</strong><br /><span className="muted">{c.email}</span></td>
                        <td>{c.role === 'ADMIN' ? 'Quản trị viên' : 'Cá nhân'}</td>
                        <td><span className={`badge ${c.status === 'ACTIVE' ? 'ok' : 'bad'}`}>{c.status === 'ACTIVE' ? 'Hoạt động' : 'Đã khoá'}</span></td>
                        <td>{date(c.createdAt)}</td>
                        <td>
                          {c.id !== me.id && (
                            <button type="button" className="ghost small" onClick={() => toggle(c)}>
                              {c.status === 'ACTIVE' ? 'Khoá' : 'Mở khoá'}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          }}
        </Loaded>
      </section>
      <p className="hint">Khoá chặn lần đăng nhập tiếp theo. Phiên đang mở của khách hàng vẫn dùng được tới khi hết hạn (tối đa 60 phút).</p>
      {LIMIT_NOTE}
    </>
  );
}

function DepositForm({ account, onDone }: { account: Account; onDone: (result: Flash) => void }) {
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const value = Number(amount);
    if (!window.confirm(`Nạp ${money(value, account.currency)} vào ${account.accountNo}?`)) return;
    setBusy(true);
    try {
      const { data } = await api<Account>('POST', `/accounts/admin/${account.id}/deposit`, { body: { amount: value } });
      setAmount('');
      onDone({ tone: 'ok', text: `Đã nạp ${money(value, account.currency)} vào ${account.accountNo}. Số dư mới ${money(data.balance, account.currency)}.` });
    } catch (err) {
      onDone({ tone: 'bad', text: errorMessage(err) });
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="row nowrap" onSubmit={submit}>
      <input type="number" min={1} max={1e12} step={1} required value={amount} onChange={(e) => setAmount(e.target.value)}
        aria-label={`Số tiền nạp vào ${account.accountNo}`} placeholder="Số tiền" className="amount" />
      <button type="submit" className="small" disabled={busy}>Nạp tiền</button>
    </form>
  );
}

export function AdminAccounts() {
  const accounts = useLoad(get<Account[]>('/accounts/admin'));
  const customers = useLoad(get<Customer[]>('/auth/admin/customers'));
  const [q, setQ] = useState('');
  const [msg, setMsg] = useState<Flash | null>(null);
  const owner = new Map((customers.data ?? []).map((c) => [c.id, c]));
  const done = (m: Flash) => { setMsg(m); accounts.reload(); };

  return (
    <>
      <PageHead title="Tài khoản hệ thống"><Search value={q} onChange={setQ} label="Tìm tài khoản, email" /></PageHead>
      {msg && <p className={`alert ${msg.tone}`} role="status">{msg.text}</p>}
      <section className="panel">
        <Loaded state={accounts} empty={<p className="muted">Chưa có tài khoản nào.</p>}>
          {(list) => {
            const needle = q.trim().toLowerCase();
            const shown = list.filter((a) => !needle || a.accountNo.toLowerCase().includes(needle) || owner.get(a.customerId)?.email.includes(needle));
            return (
              <div className="table-wrap">
                <table>
                  <thead><tr><th scope="col">Tài khoản</th><th scope="col">Chủ tài khoản</th><th scope="col" className="num">Số dư</th><th scope="col">Nạp tiền</th></tr></thead>
                  <tbody>
                    {shown.map((a) => (
                      <tr key={a.id}>
                        <td><strong>{a.accountNo}</strong><br /><span className="muted">Mở {date(a.createdAt)}</span></td>
                        <td>{owner.get(a.customerId)?.email ?? <span className="mono">{shortId(a.customerId)}</span>}</td>
                        <td className="num"><Money value={a.balance} currency={a.currency} /></td>
                        <td><DepositForm account={a} onDone={done} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          }}
        </Loaded>
      </section>
      <p className="hint">Nạp tiền ghi một khoản tiền vào trên sao kê của khách hàng với mã tham chiếu mới.</p>
      {LIMIT_NOTE}
    </>
  );
}

export function AdminTransfers() {
  const transfers = useLoad(get<Transfer[]>('/transfers/admin'));
  const accounts = useLoad(get<Account[]>('/accounts/admin'));
  const [filter, setFilter] = useState<TransferStatus | ''>('');
  const accountNo = new Map((accounts.data ?? []).map((a) => [a.id, a.accountNo]));
  const label = (id: string) => accountNo.get(id) ?? shortId(id);

  return (
    <>
      <PageHead title="Giao dịch hệ thống">
        <button type="button" className="ghost" onClick={transfers.reload}>Làm mới</button>
      </PageHead>
      <div className="segmented" role="group" aria-label="Lọc theo trạng thái">
        {STATUS_FILTERS.map(([k, l]) => (
          <button key={k} type="button" aria-pressed={filter === k} onClick={() => setFilter(k)}>{l}</button>
        ))}
      </div>
      <section className="panel">
        <Loaded state={transfers} empty={<p className="muted">Chưa có giao dịch nào.</p>}>
          {(list) => (
            <div className="table-wrap">
              <table>
                <thead><tr><th scope="col">Thời gian</th><th scope="col">Từ</th><th scope="col">Đến</th><th scope="col" className="num">Số tiền</th><th scope="col">Trạng thái</th></tr></thead>
                <tbody>
                  {list.filter((t) => !filter || t.status === filter).map((t) => (
                    <tr key={t.id}>
                      <td>{dateTime(t.createdAt)}<br /><span className="mono muted">{shortId(t.id)}</span></td>
                      <td>{label(t.sourceAccountId)}</td>
                      <td>{label(t.destAccountId)}</td>
                      <td className="num"><Money value={t.amount} currency={t.currency} /></td>
                      <td><StatusBadge status={t.status} />{t.failureReason && <p className="hint">{reasonText(t.failureReason)}</p>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Loaded>
      </section>
      {LIMIT_NOTE}
    </>
  );
}
