import { useCallback, useState } from 'react';
import type { Account, Customer, Message, Transfer, TransferStatus } from '../types.ts';
import { api, get } from '../gateway.ts';
import { STATUS, date, formatAccountNo, groupByDay, money, parseAmount, reasonText, shortId, statusCounts, time, totalsByCurrency } from '../format.ts';
import { useLoad, useSession } from '../hooks.ts';
import { ConfirmDialog, EmptyState, Amount, Loaded, PageHead, StatBlock, StatusBadge, Toast } from '../ui.tsx';
import { StatusFilter } from './user.tsx';

// Every /admin API is ROLE_ADMIN-only on the server; these pages are hidden from users, not trusted to protect.

const LIMIT_NOTE = <p className="hint">Hiển thị tối đa 200 bản ghi mới nhất.</p>;

function useToast() {
  const [message, setMessage] = useState<Message | null>(null);
  const close = useCallback(() => setMessage(null), []);
  return [<Toast message={message} onClose={close} />, setMessage] as const;
}

function Search({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  return (
    <span className="field" style={{ width: 'min(100%, 300px)' }}>
      <input type="search" placeholder={label} aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} />
    </span>
  );
}

export function AdminCustomers() {
  const { customer: me } = useSession()!;
  const customers = useLoad(get<Customer[]>('/auth/admin/customers'));
  const [q, setQ] = useState('');
  const [toast, notify] = useToast();
  const [pending, setPending] = useState<Customer | null>(null);
  const [busy, setBusy] = useState(false);

  const toggle = async (c: Customer) => {
    const locking = c.status === 'ACTIVE';
    setBusy(true);
    try {
      await api('POST', `/auth/admin/customers/${c.id}/${locking ? 'lock' : 'unlock'}`);
      notify({ tone: 'ok', text: `${locking ? 'Đã khoá' : 'Đã mở khoá'} ${c.email}.` });
      customers.reload();
    } catch (err) {
      notify({ tone: 'bad', text: (err as Error).message });
    } finally {
      setBusy(false);
      setPending(null);
    }
  };
  // Unlocking is harmless and reversible, so only locking asks first.
  const ask = (c: Customer) => (c.status === 'ACTIVE' ? setPending(c) : toggle(c));

  const list = customers.data ?? [];
  return (
    <>
      <PageHead title="Khách hàng"><Search value={q} onChange={setQ} label="Tìm tên, email" /></PageHead>
      <section className="stats">
        <StatBlock label="Khách hàng" value={list.length} />
        <StatBlock label="Đã khoá" value={list.filter((c) => c.status !== 'ACTIVE').length} />
      </section>
      <section className="block">
        <Loaded state={customers} empty={<EmptyState title="Chưa có khách hàng" />}>
          {(data) => {
            const needle = q.trim().toLowerCase();
            const shown = data.filter((c) => !needle || c.email.toLowerCase().includes(needle) || c.fullName.toLowerCase().includes(needle));
            return (
              <div className="table-wrap">
                <table>
                  <thead><tr><th scope="col">Khách hàng</th><th scope="col">Loại</th><th scope="col">Trạng thái</th><th scope="col">Ngày tham gia</th><th scope="col"><span className="sr-only">Thao tác</span></th></tr></thead>
                  <tbody>
                    {shown.map((c) => (
                      <tr key={c.id}>
                        <td>{c.fullName}<br /><span className="hint break">{c.email}</span></td>
                        <td>{c.role === 'ADMIN' ? 'Quản trị viên' : 'Cá nhân'}</td>
                        <td><span className={`badge ${c.status === 'ACTIVE' ? 'ok' : 'bad'}`}>{c.status === 'ACTIVE' ? 'Hoạt động' : 'Đã khoá'}</span></td>
                        <td>{date(c.createdAt)}</td>
                        <td className="num">
                          {c.id !== me.id && (
                            <button type="button" className="btn btn-frost small" onClick={() => ask(c)} disabled={busy}>
                              {c.status === 'ACTIVE' ? 'Khoá' : 'Mở khoá'}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!shown.length && <p className="muted" style={{ padding: 12 }}>Không có khách hàng khớp “{q}”.</p>}
              </div>
            );
          }}
        </Loaded>
      </section>
      <p className="hint">Khoá chặn lần đăng nhập tiếp theo. Phiên đang mở của khách hàng vẫn dùng được tới khi hết hạn (tối đa 60 phút).</p>
      {LIMIT_NOTE}
      <ConfirmDialog open={!!pending} title="Khoá khách hàng?" confirmLabel="Khoá" busy={busy}
        onConfirm={() => pending && toggle(pending)} onCancel={() => setPending(null)}>
        {pending && <>Khoá {pending.email}? Khách hàng sẽ không đăng nhập được nữa.</>}
      </ConfirmDialog>
      {toast}
    </>
  );
}

function DepositForm({ account, onAsk }: { account: Account; onAsk: (account: Account, value: number, clear: () => void) => void }) {
  const [text, setText] = useState('');
  const value = parseAmount(text);
  const valid = value != null && value > 0 && value <= 1e12;
  return (
    <form className="row" style={{ flexWrap: 'nowrap' }} onSubmit={(e) => { e.preventDefault(); if (valid) onAsk(account, value, () => setText('')); }}>
      <span className={`field ${text && !valid ? 'invalid' : ''}`} style={{ width: 160, minHeight: 40 }}>
        <input inputMode="numeric" value={text} onChange={(e) => setText(e.target.value)} placeholder="Số tiền"
          aria-label={`Số tiền nạp vào ${account.accountNo}`} style={{ height: 38 }} />
      </span>
      <button type="submit" className="btn btn-frost small" disabled={!valid}>Nạp</button>
    </form>
  );
}

export function AdminAccounts() {
  const accounts = useLoad(get<Account[]>('/accounts/admin'));
  const customers = useLoad(get<Customer[]>('/auth/admin/customers'));
  const [q, setQ] = useState('');
  const [toast, notify] = useToast();
  const [pending, setPending] = useState<{ account: Account; value: number; clear: () => void; key: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const owner = new Map((customers.data ?? []).map((c) => [c.id, c]));

  const deposit = async () => {
    if (!pending) return;
    const { account, value, clear } = pending;
    setBusy(true);
    try {
      const { data } = await api<Account>('POST', `/accounts/admin/${account.id}/deposit`, { body: { amount: value }, headers: { 'Idempotency-Key': pending.key } });
      clear();
      notify({ tone: 'ok', text: `Đã nạp ${money(value, account.currency)} vào ${formatAccountNo(account.accountNo)}. Số dư mới ${money(data.balance, account.currency)}.` });
      accounts.reload();
    } catch (err) {
      notify({ tone: 'bad', text: (err as Error).message });
    } finally {
      setBusy(false);
      setPending(null);
    }
  };

  const list = accounts.data ?? [];
  return (
    <>
      <PageHead title="Tài khoản hệ thống"><Search value={q} onChange={setQ} label="Tìm tài khoản, email" /></PageHead>
      <section className="stats">
        <StatBlock label="Tài khoản" value={list.length} />
        {totalsByCurrency(list).map(([cur, sum]) => <StatBlock key={cur} label={`Tổng ${cur}`} value={<Amount value={sum} currency={cur} size="md" />} />)}
      </section>
      <section className="block">
        <Loaded state={accounts} empty={<EmptyState title="Chưa có tài khoản nào" />}>
          {(data) => {
            const needle = q.trim().toLowerCase();
            const shown = data.filter((a) => !needle || a.accountNo.toLowerCase().includes(needle) || owner.get(a.customerId)?.email.toLowerCase().includes(needle));
            return (
              <div className="table-wrap">
                <table>
                  <thead><tr><th scope="col">Tài khoản</th><th scope="col">Chủ tài khoản</th><th scope="col" className="num">Số dư</th><th scope="col">Nạp tiền</th></tr></thead>
                  <tbody>
                    {shown.map((a) => (
                      <tr key={a.id}>
                        <td><span className="acct">{formatAccountNo(a.accountNo)}</span><br /><span className="hint">Mở {date(a.createdAt)}</span></td>
                        <td className="break">{owner.get(a.customerId)?.email ?? <span className="acct">{shortId(a.customerId)}</span>}</td>
                        <td className="num"><Amount value={a.balance} currency={a.currency} /></td>
                        <td><DepositForm account={a} onAsk={(account, value, clear) => setPending({ account, value, clear, key: crypto.randomUUID() })} /></td>
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
      <ConfirmDialog open={!!pending} title="Nạp tiền?" confirmLabel="Nạp tiền" busy={busy} onConfirm={deposit} onCancel={() => setPending(null)}>
        {pending && <>Nạp {money(pending.value, pending.account.currency)} vào {formatAccountNo(pending.account.accountNo)}?</>}
      </ConfirmDialog>
      {toast}
    </>
  );
}

export function AdminTransfers() {
  const transfers = useLoad(get<Transfer[]>('/transfers/admin'));
  const accounts = useLoad(get<Account[]>('/accounts/admin'));
  const [filter, setFilter] = useState('');
  const accountNo = new Map((accounts.data ?? []).map((a) => [a.id, formatAccountNo(a.accountNo)]));
  const label = (id: string) => accountNo.get(id) ?? shortId(id);
  const counts = statusCounts(transfers.data ?? []);

  return (
    <>
      <PageHead title="Giao dịch hệ thống">
        <button type="button" className="btn btn-frost" onClick={transfers.reload}>Làm mới</button>
      </PageHead>
      <section className="stats">
        {(Object.keys(STATUS) as TransferStatus[]).map((k) => <StatBlock key={k} label={STATUS[k].label} value={counts[k]} />)}
      </section>
      <div><StatusFilter value={filter} onChange={setFilter} /></div>
      <section className="block">
        <Loaded state={transfers} empty={<EmptyState title="Chưa có giao dịch nào" />}>
          {(list) => {
            const shown = list.filter((t) => !filter || t.status === filter);
            if (!shown.length) return <p className="muted">Không có giao dịch nào ở trạng thái này.</p>;
            return (
              <div className="table-wrap">
                <table>
                  <thead><tr><th scope="col">Giờ</th><th scope="col">Từ</th><th scope="col">Đến</th><th scope="col" className="num">Số tiền</th><th scope="col">Trạng thái</th></tr></thead>
                  {groupByDay(shown).map(([day, group]) => (
                  <tbody key={day}>
                    <tr><th scope="rowgroup" colSpan={5} className="day-label day-row">{day}</th></tr>
                    {group.map((t) => (
                      <tr key={t.id}>
                        <td>{time(t.createdAt)}<br /><span className="acct hint">{shortId(t.id)}</span></td>
                        <td className="acct">{label(t.sourceAccountId)}</td>
                        <td className="acct">{label(t.destAccountId)}</td>
                        <td className="num"><Amount value={t.amount} currency={t.currency} struck={t.status === 'FAILED'} /></td>
                        <td><StatusBadge status={t.status} />{t.failureReason && <p className="hint" style={{ marginTop: 6, maxWidth: 260 }}>{reasonText(t.failureReason)}</p>}</td>
                      </tr>
                    ))}
                  </tbody>
                  ))}
                </table>
              </div>
            );
          }}
        </Loaded>
      </section>
      {LIMIT_NOTE}
    </>
  );
}
