// Session lives in sessionStorage so a refresh keeps you signed in, but closing the tab signs you out.
// shortcut: token readable by page scripts (no httpOnly cookie support in auth-service); move to a cookie session for production.
const KEY = 'banking.session';

function read() {
  try {
    const s = JSON.parse(sessionStorage.getItem(KEY));
    return s && new Date(s.expiresAt) > new Date() ? s : null;
  } catch {
    return null;
  }
}

let session = read();
const listeners = new Set();

export const getSession = () => session;
export const onSessionChange = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };

export function setSession(next) {
  session = next;
  try {
    if (next) sessionStorage.setItem(KEY, JSON.stringify(next));
    else sessionStorage.removeItem(KEY);
  } catch { /* storage blocked: session stays in memory only */ }
  listeners.forEach((fn) => fn(next));
}

export class ApiError extends Error {
  constructor(status, message, data) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

export function messageFor(status, data) {
  if (status === 0 || status >= 502 || (status === 500 && !data)) {
    return 'Không kết nối được máy chủ. Kiểm tra kết nối mạng rồi thử lại.';
  }
  if (Array.isArray(data?.errors)) return data.errors.join('; ');
  return data?.detail || data?.error || `Yêu cầu thất bại (HTTP ${status}).`;
}

export async function api(method, path, { body, headers = {} } = {}) {
  const token = session?.accessToken;
  let res;
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
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (res.status === 401 && token) setSession(null); // expired or revoked: back to sign-in
  if (!res.ok) throw new ApiError(res.status, messageFor(res.status, data), data);
  return { status: res.status, data };
}

export async function login(email, password) {
  const { data } = await api('POST', '/auth/login', { body: { email, password } });
  setSession({ accessToken: data.accessToken, expiresAt: data.expiresAt, customer: data.customer });
  return data.customer;
}

export const logout = () => setSession(null);
