import type { ErrorBody, Transfer } from './types';

const MAX_LOG = 200;
let token: string | null = null;
let onLog: () => void = () => {};
let onExpired: () => void = () => {};

export interface LogEntry {
  method: string;
  path: string;
  status: number;
  ms: number;
  headers: string;
  body: string;
}

/**
 * `data` is the parsed body for any status: T on success, an error body (or text) otherwise.
 * Callers check `status` before reading `data` as T.
 */
export interface ApiResult<T = unknown> {
  status: number;
  data: T;
}

export interface ApiOptions {
  body?: unknown;
  headers?: Record<string, string>;
  /** false: send no JWT even when signed in. */
  auth?: boolean;
}

export const log: LogEntry[] = [];
export const setToken = (t: string | null): void => { token = t; };
export const hooks = (h: { onLog: () => void; onExpired: () => void }): void => { onLog = h.onLog; onExpired = h.onExpired; };

// Never returns a rejected promise: status 0 means the request did not reach the proxy.
export async function api<T = unknown>(method: string, path: string, { body, headers = {}, auth = true }: ApiOptions = {}): Promise<ApiResult<T>> {
  const started = performance.now();
  const sentToken = auth ? token : null;
  const result: ApiResult<unknown> = { status: 0, data: null };
  try {
    const res = await fetch(path, {
      method,
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(sentToken ? { Authorization: `Bearer ${sentToken}` } : {}),
        ...headers,
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    result.status = res.status;
    const text = await res.text();
    try { result.data = text ? JSON.parse(text) : null; } catch { result.data = text; }
  } catch (e) {
    result.data = { error: e instanceof Error ? e.message : String(e) };
  }
  log.unshift({
    method, path, status: result.status,
    ms: Math.round(performance.now() - started),
    headers: headers['Idempotency-Key'] ? `Idempotency-Key: ${headers['Idempotency-Key']}` : '',
    // Request bodies hold passwords and login responses hold the token: keep neither in the log.
    body: JSON.stringify(result.data, (k, v: unknown) => (k === 'accessToken' ? '[đã ẩn]' : v), 2),
  });
  log.length = Math.min(log.length, MAX_LOG);
  onLog();
  if (result.status === 401 && sentToken) onExpired();
  return result as ApiResult<T>;
}

export function errorText({ status, data }: ApiResult<unknown>): string {
  if (status === 0 || status >= 502 || (status === 500 && !data)) {
    return `Không kết nối được gateway (HTTP ${status}). Kiểm tra backend đã chạy chưa.`;
  }
  const body = data !== null && typeof data === 'object' ? (data as ErrorBody) : null;
  const errors = body?.errors;
  if (Array.isArray(errors)) return errors.join('; ');
  return body?.detail || body?.error || `HTTP ${status}`;
}

const TERMINAL_WAIT_MS = 60000;

/** Last known state of a transfer; `pollError` is set when polling stopped on an error response. */
export type TransferPoll = Partial<Transfer> & { pollError?: string };

// Saga runs asynchronously over Kafka, so the result only shows up by polling.
export async function waitForTransfer(id: string): Promise<TransferPoll | null> {
  const deadline = Date.now() + TERMINAL_WAIT_MS;
  let last: Transfer | null = null;
  while (Date.now() < deadline) {
    const r = await api<Transfer>('GET', `/transfers/${id}`);
    if (r.status !== 200) return r.data ? { ...last, pollError: errorText(r) } : last;
    last = r.data;
    if (last.status !== 'PENDING') return last;
    await new Promise((done) => setTimeout(done, 1000));
  }
  return last;
}
