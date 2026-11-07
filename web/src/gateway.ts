import type { Customer, ErrorBody, LoginResponse, Session } from './types';

// Session lives in sessionStorage so a refresh keeps you signed in, but closing the tab signs you out.
// shortcut: token readable by page scripts (no httpOnly cookie support in auth-service); move to a cookie session for production.
const KEY = 'banking.session';

function read(): Session | null {
  try {
    const s = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Session | null;
    return s && new Date(s.expiresAt) > new Date() ? s : null;
  } catch {
    return null; // no storage (blocked, or not a browser) or a corrupt entry
  }
}

type Listener = (session: Session | null) => void;

let session = read();
const listeners = new Set<Listener>();

export const getSession = (): Session | null => session;
export const onSessionChange = (fn: Listener): (() => void) => {
  listeners.add(fn);
  return () => { listeners.delete(fn); };
};

export function setSession(next: Session | null): void {
  session = next;
  try {
    if (next) sessionStorage.setItem(KEY, JSON.stringify(next));
    else sessionStorage.removeItem(KEY);
  } catch { /* storage blocked: session stays in memory only */ }
  listeners.forEach((fn) => fn(next));
}

export class ApiError extends Error {
  readonly status: number;
  readonly data: unknown;

  constructor(status: number, message: string, data?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
  }
}

const errorBody = (data: unknown): ErrorBody | null => (data !== null && typeof data === 'object' ? (data as ErrorBody) : null);

export function messageFor(status: number, data?: unknown): string {
  if (status === 0 || status >= 502 || (status === 500 && !data)) {
    return 'Không kết nối được máy chủ. Kiểm tra kết nối mạng rồi thử lại.';
  }
  const body = errorBody(data);
  const errors = body?.errors;
  if (Array.isArray(errors)) return errors.join('; ');
  return body?.detail || body?.error || `Yêu cầu thất bại (HTTP ${status}).`;
}

/** Text to show for anything a handler caught: an ApiError, a thrown Error, or something stranger. */
export const errorMessage = (err: unknown): string => (err instanceof Error ? err.message : String(err));

export type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface ApiOptions {
  body?: unknown;
  headers?: Record<string, string>;
}

export interface ApiResponse<T> {
  status: number;
  data: T;
}

/** Calls the gateway. Resolves only for 2xx (typed as T); every other outcome rejects with an ApiError. */
export async function api<T = unknown>(method: Method, path: string, { body, headers = {} }: ApiOptions = {}): Promise<ApiResponse<T>> {
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
  let data: unknown = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (res.status === 401 && token) setSession(null); // expired or revoked: back to sign-in
  if (!res.ok) throw new ApiError(res.status, messageFor(res.status, data), data);
  return { status: res.status, data: data as T };
}

export async function login(email: string, password: string): Promise<Customer> {
  const { data } = await api<LoginResponse>('POST', '/auth/login', { body: { email, password } });
  setSession({ accessToken: data.accessToken, expiresAt: data.expiresAt, customer: data.customer });
  return data.customer;
}

export const logout = (): void => setSession(null);
