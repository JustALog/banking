import { api, waitForTransfer } from './gateway';
import type { TransferPoll } from './gateway';
import type { Account, LoginResponse, Transfer } from './types';

export interface Outcome {
  pass: boolean;
  actual: string;
}

export interface Scenario {
  name: string;
  expected: string;
  run: () => Promise<Outcome>;
}

const IDEMPOTENCY = 'Idempotency-Key';
const AMOUNT_OVER_FRAUD_LIMIT = 5000001; // banking.fraud.amount-threshold is 5,000,000
const VELOCITY_MAX = 5; // banking.fraud.velocity-max-transfers

const post = (source: string, dest: string, amount: number, key?: string) => api<Transfer>('POST', '/transfers', {
  body: { sourceAccountId: source, destAccountId: dest, amount, currency: 'VND' },
  headers: key ? { [IDEMPOTENCY]: key } : {},
});

async function open(): Promise<string> {
  const r = await api<Account>('POST', '/accounts', { body: { currency: 'VND' } });
  if (r.status !== 201) throw new Error(`Không mở được tài khoản (HTTP ${r.status})`);
  return r.data.id;
}

// New empty accounts keep each scenario independent of balances and of the per-account velocity window.
const pair = async (): Promise<[string, string]> => [await open(), await open()];

async function transferOutcome(source: string, dest: string, amount: number): Promise<TransferPoll | null> {
  const r = await post(source, dest, amount, crypto.randomUUID());
  if (r.status !== 202) throw new Error(`POST /transfers trả HTTP ${r.status}`);
  return waitForTransfer(r.data.id);
}

const failedWith = (t: TransferPoll | null, reason: string): Outcome => ({
  pass: t?.status === 'FAILED' && t.failureReason === reason,
  actual: `${t?.status ?? 'không có kết quả'} / ${t?.failureReason ?? '-'}`,
});

export const scenarios: Scenario[] = [
  {
    name: 'Gọi API không có JWT',
    expected: 'Gateway trả HTTP 401',
    async run() {
      const r = await api('GET', '/accounts', { auth: false });
      return { pass: r.status === 401, actual: `HTTP ${r.status}` };
    },
  },
  {
    name: 'USER gọi API quản trị',
    expected: 'Người dùng thường nhận HTTP 403 ở cả ba API /admin',
    async run() {
      const email = `scenario-${crypto.randomUUID()}@test.local`;
      const password = 'scenario-password';
      await api('POST', '/auth/register', { auth: false, body: { email, password, fullName: 'Scenario user' } });
      const login = await api<LoginResponse>('POST', '/auth/login', { auth: false, body: { email, password } });
      if (login.status !== 200) throw new Error(`Không đăng nhập được người dùng thử (HTTP ${login.status})`);
      // Fresh USER token instead of the signed-in one, which may be an admin.
      const headers = { Authorization: `Bearer ${login.data.accessToken}` };
      const codes: number[] = [];
      for (const path of ['/auth/admin/customers', '/accounts/admin', '/transfers/admin']) {
        codes.push((await api('GET', path, { auth: false, headers })).status);
      }
      return { pass: codes.every((c) => c === 403), actual: `HTTP ${codes.join(', ')}` };
    },
  },
  {
    name: 'Chặn endpoint nội bộ',
    expected: 'GET /accounts/internal/{id} qua gateway trả HTTP 404',
    async run() {
      const r = await api('GET', `/accounts/internal/${await open()}`);
      return { pass: r.status === 404, actual: `HTTP ${r.status}` };
    },
  },
  {
    name: 'Thiếu Idempotency-Key',
    expected: 'Gateway trả HTTP 400',
    async run() {
      const [source, dest] = await pair();
      const r = await post(source, dest, 1000);
      return { pass: r.status === 400, actual: `HTTP ${r.status}` };
    },
  },
  {
    name: 'Gửi lại cùng Idempotency-Key',
    expected: 'Lần đầu HTTP 202, lần sau HTTP 200 với cùng mã giao dịch',
    async run() {
      const [source, dest] = await pair();
      const key = crypto.randomUUID();
      const a = await post(source, dest, 1000, key);
      const b = await post(source, dest, 1000, key);
      return {
        pass: a.status === 202 && b.status === 200 && a.data?.id === b.data?.id,
        actual: `HTTP ${a.status} rồi ${b.status}, ${a.data?.id === b.data?.id ? 'cùng mã' : 'khác mã'}`,
      };
    },
  },
  {
    name: 'Vượt ngưỡng fraud',
    expected: `Lệnh ${AMOUNT_OVER_FRAUD_LIMIT.toLocaleString('vi-VN')} kết thúc FAILED / FRAUD_REJECTED`,
    async run() {
      const [source, dest] = await pair();
      return failedWith(await transferOutcome(source, dest, AMOUNT_OVER_FRAUD_LIMIT), 'FRAUD_REJECTED');
    },
  },
  {
    name: 'Không đủ số dư',
    expected: 'Chuyển từ tài khoản rỗng kết thúc FAILED / INSUFFICIENT_FUNDS',
    async run() {
      const [source, dest] = await pair();
      return failedWith(await transferOutcome(source, dest, 1000), 'INSUFFICIENT_FUNDS');
    },
  },
  {
    name: 'Chuyển quá nhanh (velocity)',
    expected: `Lệnh thứ ${VELOCITY_MAX + 1} trong 60 giây kết thúc FAILED / FRAUD_REJECTED`,
    async run() {
      const [source, dest] = await pair();
      let last: TransferPoll | null = null;
      // Sequential on purpose: fraud-service counts earlier evaluations for this account.
      for (let i = 0; i <= VELOCITY_MAX; i += 1) last = await transferOutcome(source, dest, 1000);
      return failedWith(last, 'FRAUD_REJECTED');
    },
  },
];
