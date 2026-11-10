import { useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { api, get } from '../gateway.ts';
import { STATUS, date, formatAccountNo, shortId, time, totalsByCurrency } from '../format.ts';
import { useAccounts, useLoad, useSession } from '../hooks.ts';
import type { Account, Customer, Entry, Message, Notification, Transfer } from '../types.ts';
import { Amount, CopyButton, DayGroups, EmptyState, Loaded, PageHead, StatusBadge } from '../ui.tsx';

export const accountLabel = (accounts: Account[] | null | undefined, id: string) => {
  const no = accounts?.find((a) => a.id === id)?.accountNo;
  return no ? formatAccountNo(no) : undefined;
};

/* ---------- Dashboard ---------- */

export function Dashboard() {
  const { customer } = useSession()!;
  const accounts = useAccounts();
  const transfers = useLoad(get<Transfer[]>('/transfers'));
  const notes = useLoad(get<Notification[]>('/notifications'));
  const list = accounts.data ?? [];

  return (
    <>
      <section className="balance" aria-label="Tổng số dư">
        <div className="spread">
          <p className="muted">Xin chào, {customer.fullName}</p>
          <div className="row">
            <Link className="btn btn-frost" to="/app/accounts">Mở tài khoản</Link>
            {list.length === 1 && <CopyButton text={list[0].id} label="Sao chép mã nhận tiền" className="btn btn-frost" />}
            <Link className="btn btn-primary" to="/app/transfer">Chuyển tiền</Link>
          </div>
        </div>
        <Loaded state={accounts} empty={<p className="muted">Bạn chưa có tài khoản nào.</p>}>
          {(data) => (
            <div className="balance-figures">
              {totalsByCurrency(data).map(([cur, sum], i) => (
                <div key={cur} className="stack" style={{ gap: 8 }}>
                  <Amount value={sum} currency={cur} size={i === 0 ? 'lg' : 'md'} />
                  <p className="label">Tổng số dư {cur}, {data.filter((a) => a.currency === cur).length} tài khoản</p>
                </div>
              ))}
            </div>
          )}
        </Loaded>
      </section>

      <Loaded state={accounts} empty={<NoAccount reload={accounts.reload} />}>
        {(data) => <div className="grid">{data.map((a) => <AccountCard key={a.id} account={a} />)}</div>}
      </Loaded>

      <div className="split">
        <section className="block">
          <div className="block-head"><h2>Giao dịch gần đây</h2><Link to="/app/transactions">Xem tất cả</Link></div>
          <Loaded state={transfers} empty={<EmptyState title="Chưa có giao dịch nào" action={<Link className="btn btn-frost" to="/app/transfer">Chuyển khoản đầu tiên</Link>} />}>
            {(data) => <TransferList transfers={data.slice(0, 6)} accounts={accounts.data} />}
          </Loaded>
        </section>
        <section className="block">
          <div className="block-head"><h2>Thông báo</h2><Link to="/app/notifications">Xem tất cả</Link></div>
          <Loaded state={notes} empty={<EmptyState title="Chưa có thông báo">Bạn sẽ nhận thông báo khi một giao dịch kết thúc.</EmptyState>}>
            {(data) => (
              <ul className="rows">
                {data.slice(0, 4).map((n) => (
                  <li key={n.id} className="row-item">
                    <span className="row-main"><span>{n.title}</span><span className="hint">{time(n.createdAt)}, {date(n.createdAt)}</span></span>
                  </li>
                ))}
              </ul>
            )}
          </Loaded>
        </section>
      </div>
    </>
  );
}

function NoAccount({ reload }: { reload: () => void }) {
  return (
    <EmptyState title="Bạn chưa có tài khoản thanh toán" action={<OpenAccount onOpened={reload} />}>
      Mở một tài khoản để nhận và chuyển tiền.
    </EmptyState>
  );
}

function AccountCard({ account }: { account: Account }) {
  return (
    <Link to={`/app/accounts/${account.id}`} className="object account-card">
      <span className="spread">
        <span className="acct">{formatAccountNo(account.accountNo)}</span>
        <span className="label">Thanh toán</span>
      </span>
      <Amount value={account.balance} currency={account.currency} size="md" />
    </Link>
  );
}

export function TransferList({ transfers, accounts }: { transfers: Transfer[]; accounts: Account[] | null }) {
  return (
    <DayGroups items={transfers}>
      {(t) => (
        <li key={t.id}>
          <Link className="row-link" to={`/app/transactions/${t.id}`}>
            <span className="row-main">
              <span>Đến <span className="acct">{accountLabel(accounts, t.destAccountId) ?? shortId(t.destAccountId)}</span></span>
              <span className="hint">{time(t.createdAt)}</span>
            </span>
            <span className="row-side">
              {/* A failed transfer never moved money, so it is struck through instead of signed. */}
              <Amount value={t.amount} currency={t.currency} sign={t.status === 'FAILED' ? undefined : '−'} struck={t.status === 'FAILED'} />
              <StatusBadge status={t.status} />
            </span>
          </Link>
        </li>
      )}
    </DayGroups>
  );
}

/* ---------- Accounts ---------- */

function OpenAccount({ onOpened }: { onOpened?: () => void }) {
  const [currency, setCurrency] = useState('VND');
  const [msg, setMsg] = useState<Message | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      const { data } = await api<Account>('POST', '/accounts', { body: { currency } });
      setMsg({ tone: 'ok', text: `Đã mở tài khoản ${formatAccountNo(data.accountNo)}.` });
      onOpened?.();
    } catch (err) {
      setMsg({ tone: 'bad', text: (err as Error).message });
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="stack" onSubmit={submit}>
      <div className="row">
        <span className="field" style={{ width: 140 }}>
          <select aria-label="Loại tiền" value={currency} onChange={(e) => setCurrency(e.target.value)}>
            <option value="VND">VND</option><option value="USD">USD</option><option value="EUR">EUR</option>
          </select>
        </span>
        <button type="submit" className="btn btn-frost" disabled={busy}>{busy ? 'Đang mở…' : 'Mở tài khoản'}</button>
      </div>
      {msg && <p className={`alert ${msg.tone}`} role="status">{msg.text}</p>}
    </form>
  );
}

export function Accounts() {
  const accounts = useAccounts();
  return (
    <>
      <PageHead title="Tài khoản" lead="Mỗi tài khoản có số dư và sao kê riêng. Chuyển tiền chỉ đi giữa hai tài khoản cùng loại tiền." />
      <Loaded state={accounts} empty={<NoAccount reload={accounts.reload} />}>
        {(list) => (
          <>
            <div className="grid">{list.map((a) => <AccountCard key={a.id} account={a} />)}</div>
            <section className="block">
              <div className="block-head"><h2>Mở thêm tài khoản</h2></div>
              <div style={{ paddingTop: 16 }}><OpenAccount onOpened={accounts.reload} /></div>
            </section>
          </>
        )}
      </Loaded>
    </>
  );
}

export function AccountDetail() {
  const { id } = useParams();
  const account = useLoad(get<Account>(`/accounts/${id}`), [id]);
  const entries = useLoad(get<Entry[]>(`/accounts/${id}/entries`), [id]);
  return (
    <Loaded state={account}>
      {(a) => (
        <>
          <section className="balance">
            <div className="spread">
              <div>
                <h1 className="acct">{formatAccountNo(a.accountNo)}</h1>
                <p className="muted">Tài khoản thanh toán {a.currency}, mở ngày {date(a.createdAt)}</p>
              </div>
              <Link className="btn btn-primary" to={`/app/transfer?from=${a.id}`}>Chuyển tiền từ tài khoản này</Link>
            </div>
            <div className="stack" style={{ gap: 8 }}>
              <Amount value={a.balance} currency={a.currency} size="lg" />
              <p className="label">Số dư khả dụng</p>
            </div>
          </section>
          <div className="split">
            <section className="block">
              <div className="block-head"><h2>Sao kê</h2></div>
              <Loaded state={entries} empty={<EmptyState title="Chưa có biến động số dư">Tiền vào và tiền ra của tài khoản này sẽ hiện ở đây.</EmptyState>}>
                {(list) => (
                  <DayGroups items={list}>
                    {(e) => (
                      <li key={e.id} className="row-item">
                        <span className="row-main">
                          <span>{e.direction === 'CREDIT' ? 'Tiền vào' : 'Tiền ra'}</span>
                          <span className="hint">{time(e.createdAt)}, mã tham chiếu {shortId(e.transferId)}</span>
                        </span>
                        <Amount value={e.amount} currency={a.currency} sign={e.direction === 'CREDIT' ? '+' : '−'} />
                      </li>
                    )}
                  </DayGroups>
                )}
              </Loaded>
            </section>
            <section className="object stack">
              <h2>Nhận tiền vào tài khoản này</h2>
              <p className="muted">Gửi mã nhận tiền cho người chuyển. Họ dán mã này ở bước chọn người nhận.</p>
              <p className="break acct hint" style={{ color: 'var(--fg)' }}>{a.id}</p>
              <div><CopyButton text={a.id} label="Sao chép mã nhận tiền" /></div>
            </section>
          </div>
        </>
      )}
    </Loaded>
  );
}

/* ---------- History, notifications, profile ---------- */

const FILTERS: [string, string][] = [['', 'Tất cả'], ...Object.entries(STATUS).map(([k, v]): [string, string] => [k, v.label])];

export function StatusFilter({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <div className="segmented" role="group" aria-label="Lọc theo trạng thái">
      {FILTERS.map(([k, label]) => (
        <button key={k} type="button" aria-pressed={value === k} onClick={() => onChange(k)}>{label}</button>
      ))}
    </div>
  );
}

export function Transactions() {
  const transfers = useLoad(get<Transfer[]>('/transfers'));
  const accounts = useAccounts();
  const [filter, setFilter] = useState('');
  return (
    <>
      <PageHead title="Lịch sử giao dịch">
        <button type="button" className="btn btn-frost" onClick={transfers.reload}>Làm mới</button>
      </PageHead>
      <section className="block">
        <div><StatusFilter value={filter} onChange={setFilter} /></div>
        <Loaded state={transfers} empty={<EmptyState title="Chưa có giao dịch nào" action={<Link className="btn btn-primary" to="/app/transfer">Chuyển khoản đầu tiên</Link>} />}>
          {(list) => {
            const shown = list.filter((t) => !filter || t.status === filter);
            return shown.length
              ? <TransferList transfers={shown} accounts={accounts.data} />
              : <EmptyState title="Không có giao dịch nào ở trạng thái này" action={<button type="button" className="btn btn-frost" onClick={() => setFilter('')}>Xem tất cả</button>} />;
          }}
        </Loaded>
      </section>
    </>
  );
}

export function Notifications() {
  const notes = useLoad(get<Notification[]>('/notifications'));
  return (
    <>
      <PageHead title="Thông báo">
        <button type="button" className="btn btn-frost" onClick={notes.reload}>Làm mới</button>
      </PageHead>
      <section className="block">
        <Loaded state={notes} empty={<EmptyState title="Chưa có thông báo">Bạn sẽ nhận thông báo khi một giao dịch kết thúc.</EmptyState>}>
          {(list) => (
            <DayGroups items={list}>
              {(n) => (
                <li key={n.id} className="row-item" style={{ alignItems: 'flex-start' }}>
                  <span className="row-main" style={{ gap: 6 }}>
                    <span>{n.title}</span>
                    <span className="muted" style={{ maxWidth: '65ch' }}>{n.content}</span>
                    {n.transferId && <Link to={`/app/transactions/${n.transferId}`} className="hint">Xem giao dịch</Link>}
                  </span>
                  <span className="row-side hint">
                    <span>{time(n.createdAt)}</span>
                    {/* The notification mock stores the customer id as recipient; only show real addresses. */}
                    <span>{n.channel === 'SMS' ? 'SMS' : 'Email'}{n.recipient?.includes('@') && ` tới ${n.recipient}`}</span>
                  </span>
                </li>
              )}
            </DayGroups>
          )}
        </Loaded>
      </section>
    </>
  );
}

export function Profile() {
  const me = useLoad(get<Customer>('/auth/me'));
  return (
    <>
      <PageHead title="Hồ sơ" />
      <Loaded state={me}>
        {(c) => (
          <section className="narrow">
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
