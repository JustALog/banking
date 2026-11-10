import type { Account, Transfer, TransferStatus } from './types.ts';

// Amounts are BIGINT minor units; VND has no sub-unit, so the stored number is shown as-is.
export const money = (n: number | string, currency = 'VND') => `${Number(n).toLocaleString('vi-VN')} ${currency}`;

export const dateTime = (iso: string) => new Date(iso).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
export const date = (iso: string) => new Date(iso).toLocaleDateString('vi-VN');
export const time = (iso: string) => new Date(iso).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });

export const shortId = (id?: string | null) => (id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '');
export const formatAccountNo = (no: string) => no.replace(/(.{4})/g, '$1 ').trim();

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const STATUS: Record<TransferStatus, { label: string; tone: string }> = {
  PENDING: { label: 'Đang xử lý', tone: 'wait' },
  COMPLETED: { label: 'Thành công', tone: 'ok' },
  FAILED: { label: 'Thất bại', tone: 'bad' },
};

export const REASONS: Record<string, string> = {
  FRAUD_REJECTED: 'Giao dịch bị chặn bởi kiểm tra an toàn (số tiền vượt ngưỡng hoặc chuyển quá nhiều lần trong thời gian ngắn).',
  INSUFFICIENT_FUNDS: 'Số dư tài khoản nguồn không đủ.',
  SOURCE_ACCOUNT_INVALID: 'Tài khoản nguồn không hợp lệ.',
  EMAIL_TAKEN: 'Email này đã được đăng ký. Hãy đăng nhập.',
  BAD_CREDENTIALS: 'Email hoặc mật khẩu không đúng.',
  CUSTOMER_LOCKED: 'Tài khoản đã bị khoá. Liên hệ ngân hàng để mở lại.',
  TOO_MANY_ATTEMPTS: 'Bạn nhập sai quá nhiều lần. Thử lại sau 15 phút.',
  ACCOUNT_NOT_FOUND: 'Không tìm thấy tài khoản nhận.',
  CURRENCY_MISMATCH: 'Hai tài khoản khác loại tiền.',
  SELF_TRANSFER_NOT_ALLOWED: 'Không thể chuyển vào chính tài khoản nguồn.',
  PARTNER_TIMEOUT: 'Ngân hàng đối tác không phản hồi kịp. Tiền đã được hoàn lại tài khoản nguồn.',
  PARTNER_FAILED: 'Ngân hàng đối tác từ chối giao dịch. Tiền đã được hoàn lại tài khoản nguồn.',
  ACCOUNT_LIMIT_REACHED: 'Bạn đã có tối đa 10 tài khoản.',
};

export const reasonText = (code?: string | null) => (code ? REASONS[code] ?? code : '');

// Validation errors arrive as {field, message}; say them per field in Vietnamese.
const FIELD_ERRORS: Record<string, string> = {
  fullName: 'Nhập họ và tên.',
  email: 'Email không đúng định dạng.',
  password: 'Mật khẩu cần từ 8 đến 100 ký tự.',
};
export function validationText(errors: unknown): string | null {
  if (!Array.isArray(errors) || !errors.length) return null;
  return errors.map((e: { field: string; message: string }) => FIELD_ERRORS[e.field] ?? e.message).join(' ');
}

// Sum balances per currency; accounts in different currencies must not be added together.
export function totalsByCurrency(accounts: Pick<Account, 'currency' | 'balance'>[]): [string, number][] {
  const totals: Record<string, number> = {};
  for (const a of accounts) totals[a.currency] = (totals[a.currency] ?? 0) + a.balance;
  return Object.entries(totals);
}

export function statusCounts(transfers: Pick<Transfer, 'status'>[]): Record<TransferStatus, number> {
  const counts = { PENDING: 0, COMPLETED: 0, FAILED: 0 };
  for (const t of transfers) if (t.status in counts) counts[t.status] += 1;
  return counts;
}

// Integer typed plainly or grouped in threes by one separator ("2.500.000", "2,500,000", "2 500 000").
// Decimals are rejected: amounts are whole minor units.
export function parseAmount(text: string): number | null {
  const s = String(text).trim();
  if (!/^\d+$/.test(s) && !/^\d{1,3}(([.,\s])\d{3})(\2\d{3})*$/.test(s)) return null;
  const n = Number(s.replace(/[.,\s]/g, ''));
  return Number.isSafeInteger(n) ? n : null;
}

// Groups items (newest first from the API) under local-day labels, keeping their order.
export function groupByDay<T extends { createdAt: string }>(items: T[], now = new Date()): [string, T[]][] {
  const today = now.toLocaleDateString('vi-VN');
  const yesterday = new Date(now.getTime() - 86400000).toLocaleDateString('vi-VN');
  const groups = new Map<string, T[]>();
  for (const item of items) {
    const d = date(item.createdAt);
    const label = d === today ? 'Hôm nay' : d === yesterday ? 'Hôm qua' : d;
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label)!.push(item);
  }
  return [...groups];
}

// The saga as three steps a customer understands. The API only exposes PENDING/COMPLETED/FAILED,
// so a pending transfer is shown at the safety check, and a failure is placed by its reason.
const SAGA = ['Đã gửi lệnh', 'Kiểm tra an toàn', 'Ghi sổ'];
export function sagaSteps({ status, failureReason }: Pick<Transfer, 'status' | 'failureReason'>): { label: string; state: 'done' | 'active' | 'failed' | 'todo' }[] {
  const at = status === 'COMPLETED' ? 3 : status === 'PENDING' || failureReason === 'FRAUD_REJECTED' ? 1 : 2;
  return SAGA.map((label, i) => ({
    label,
    state: i < at ? 'done' : i > at ? 'todo' : status === 'FAILED' ? 'failed' : 'active',
  }));
}

// First problem with a transfer draft, or '' when it can be reviewed.
export function transferProblem({ mode, from, dest, amount }: { mode: 'own' | 'other'; from: Pick<Account, 'id' | 'balance' | 'currency'>; dest: string; amount: number | null }) {
  if (!dest) return mode === 'own' ? 'Bạn chưa có tài khoản khác cùng loại tiền.' : 'Nhập mã nhận tiền của người nhận.';
  if (!UUID_RE.test(dest)) return 'Mã nhận tiền không đúng định dạng.';
  if (dest === from.id) return 'Tài khoản nhận phải khác tài khoản nguồn.';
  if (amount == null || amount <= 0) return 'Nhập số tiền là số nguyên dương.';
  if (amount > from.balance) return `Số tiền vượt số dư (${money(from.balance, from.currency)}).`;
  return '';
}
