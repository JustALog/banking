import { REASONS, validationText } from './format.ts';
import type { Customer, Session } from './types.ts';

// Session lives in sessionStorage so a refresh keeps you signed in, but closing the tab signs you out.
// shortcut: token readable by page scripts (no httpOnly cookie support in auth-service); move to a cookie session for production.
const KEY = 'banking.session';

function read(): Session | null {
  try {
    const s = JSON.parse(sessionStorage.getItem(KEY) ?? 'null');
    return s && new Date(s.expiresAt) > new Date() ? s : null;
  } catch {
    return null;
  }
}

let session = read();
const listeners = new Set<(s: Session | null) => void>();

export const getSession = () => session;
export const onSessionChange = (fn: (s: Session | null) => void) => { listeners.add(fn); return () => listeners.delete(fn); };

export function setSession(next: Session | null) {
  session = next;
  try {
    if (next) sessionStorage.setItem(KEY, JSON.stringify(next));
    else sessionStorage.removeItem(KEY);
  } catch { /* storage blocked: session stays in memory only */ }
  listeners.forEach((fn) => fn(next));
}

export class ApiError extends Error {
  status: number;
  data: any;
  constructor(status: number, message: string, data?: any) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export function messageFor(status: number, data?: any): string {
  if (status === 0 || status >= 502 || (status === 500 && !data)) {
    return 'Không kết nối được máy chủ. Kiểm tra kết nối mạng rồi thử lại.';
  }
  if (data?.code && data.code in REASONS) return REASONS[data.code];
  return validationText(data?.errors) || data?.detail || `Yêu cầu thất bại (HTTP ${status}).`;
}

export async function api<T = any>(
  method: string,
  path: string,
  { body, headers = {} }: { body?: unknown; headers?: Record<string, string> } = {},
): Promise<{ status: number; data: T }> {
  const token = session?.accessToken;
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, messageFor(0));
  }
  const text = await res.text();
  let data: any = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (res.status === 401 && token) setSession(null); // expired or revoked: back to sign-in
  if (!res.ok) throw new ApiError(res.status, messageFor(res.status, data), data);
  return { status: res.status, data };
}

export const get = <T>(path: string) => () => api<T>('GET', path).then((r) => r.data);

// Shared by login and register: both endpoints return the same session shape.
export function startSession(data: Session): void {
  setSession({ accessToken: data.accessToken, expiresAt: data.expiresAt, customer: data.customer });
}

export async function login(email: string, password: string): Promise<Customer> {
  const { data } = await api<Session>('POST', '/auth/login', { body: { email, password } });
  startSession(data);
  return data.customer;
}

export const logout = () => setSession(null);
