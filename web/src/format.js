// Amounts are BIGINT minor units; VND has no sub-unit, so the stored number is shown as-is.
export const money = (n, currency = 'VND') => `${Number(n).toLocaleString('vi-VN')} ${currency}`;

export const dateTime = (iso) => new Date(iso).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
export const date = (iso) => new Date(iso).toLocaleDateString('vi-VN');

export const shortId = (id) => (id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '');

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const STATUS = {
  PENDING: { label: 'Đang xử lý', tone: 'wait' },
  COMPLETED: { label: 'Thành công', tone: 'ok' },
  FAILED: { label: 'Thất bại', tone: 'bad' },
};

const REASONS = {
  FRAUD_REJECTED: 'Giao dịch bị chặn bởi kiểm tra an toàn (số tiền vượt ngưỡng hoặc chuyển quá nhiều lần trong thời gian ngắn).',
  INSUFFICIENT_FUNDS: 'Số dư tài khoản nguồn không đủ.',
  ACCOUNT_NOT_FOUND: 'Không tìm thấy tài khoản nhận.',
  CURRENCY_MISMATCH: 'Hai tài khoản khác loại tiền.',
  SELF_TRANSFER_NOT_ALLOWED: 'Không thể chuyển vào chính tài khoản nguồn.',
  PARTNER_TIMEOUT: 'Ngân hàng đối tác không phản hồi kịp. Tiền đã được hoàn lại tài khoản nguồn.',
  PARTNER_FAILED: 'Ngân hàng đối tác từ chối giao dịch. Tiền đã được hoàn lại tài khoản nguồn.',
};

export const reasonText = (code) => (code ? REASONS[code] ?? code : '');

// Sum balances per currency; accounts in different currencies must not be added together.
export function totalsByCurrency(accounts) {
  const totals = {};
  for (const a of accounts) totals[a.currency] = (totals[a.currency] ?? 0) + a.balance;
  return Object.entries(totals);
}
