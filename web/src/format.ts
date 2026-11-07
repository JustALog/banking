import type { Account, TransferStatus } from './types';

// Amounts are BIGINT minor units; VND has no sub-unit, so the stored number is shown as-is.
export const money = (n: number | string, currency = 'VND'): string => `${Number(n).toLocaleString('vi-VN')} ${currency}`;

export const dateTime = (iso: string): string => new Date(iso).toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
export const date = (iso: string): string => new Date(iso).toLocaleDateString('vi-VN');

export const shortId = (id: string | null | undefined): string => (id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '');

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export type Tone = 'ok' | 'bad' | 'wait';

export const STATUS: Record<TransferStatus, { label: string; tone: Tone }> = {
  PENDING: { label: 'Đang xử lý', tone: 'wait' },
  COMPLETED: { label: 'Thành công', tone: 'ok' },
  FAILED: { label: 'Thất bại', tone: 'bad' },
};

/** Options for the status filter bar: "all" first, then every transfer status. */
export const STATUS_FILTERS: Array<[TransferStatus | '', string]> = [
  ['', 'Tất cả'],
  ...(Object.keys(STATUS) as TransferStatus[]).map((k): [TransferStatus, string] => [k, STATUS[k].label]),
];

const REASONS: Record<string, string> = {
  FRAUD_REJECTED: 'Giao dịch bị chặn bởi kiểm tra an toàn (số tiền vượt ngưỡng hoặc chuyển quá nhiều lần trong thời gian ngắn).',
  INSUFFICIENT_FUNDS: 'Số dư tài khoản nguồn không đủ.',
  ACCOUNT_NOT_FOUND: 'Không tìm thấy tài khoản nhận.',
  CURRENCY_MISMATCH: 'Hai tài khoản khác loại tiền.',
  SELF_TRANSFER_NOT_ALLOWED: 'Không thể chuyển vào chính tài khoản nguồn.',
  PARTNER_TIMEOUT: 'Ngân hàng đối tác không phản hồi kịp. Tiền đã được hoàn lại tài khoản nguồn.',
  PARTNER_FAILED: 'Ngân hàng đối tác từ chối giao dịch. Tiền đã được hoàn lại tài khoản nguồn.',
};

export const reasonText = (code: string | null | undefined): string => (code ? REASONS[code] ?? code : '');

// Sum balances per currency; accounts in different currencies must not be added together.
export function totalsByCurrency(accounts: ReadonlyArray<Pick<Account, 'currency' | 'balance'>>): Array<[string, number]> {
  const totals: Record<string, number> = {};
  for (const a of accounts) totals[a.currency] = (totals[a.currency] ?? 0) + a.balance;
  return Object.entries(totals);
}
