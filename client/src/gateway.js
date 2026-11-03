const MAX_LOG = 200;
let token = null;
let onLog = () => {};
let onExpired = () => {};

export const log = [];
export const setToken = (t) => { token = t; };
export const hooks = (h) => { onLog = h.onLog; onExpired = h.onExpired; };

// Never returns a rejected promise: status 0 means the request did not reach the proxy.
export async function api(method, path, { body, headers = {}, auth = true } = {}) {
  const started = performance.now();
  const sentToken = auth ? token : null;
  const result = { status: 0, data: null };
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
    result.data = { error: e.message };
  }
  log.unshift({
    method, path, status: result.status,
    ms: Math.round(performance.now() - started),
    headers: headers['Idempotency-Key'] ? `Idempotency-Key: ${headers['Idempotency-Key']}` : '',
    // Request bodies hold passwords and login responses hold the token: keep neither in the log.
    body: JSON.stringify(result.data, (k, v) => (k === 'accessToken' ? '[đã ẩn]' : v), 2),
  });
  log.length = Math.min(log.length, MAX_LOG);
  onLog();
  if (result.status === 401 && sentToken) onExpired();
  return result;
}

export function errorText({ status, data }) {
  if (status === 0 || status >= 502 || (status === 500 && !data)) {
    return `Không kết nối được gateway (HTTP ${status}). Kiểm tra backend đã chạy chưa.`;
  }
  if (Array.isArray(data?.errors)) return data.errors.join('; ');
  return data?.detail || data?.error || `HTTP ${status}`;
}

const TERMINAL_WAIT_MS = 60000;

// Saga runs asynchronously over Kafka, so the result only shows up by polling.
export async function waitForTransfer(id) {
  const deadline = Date.now() + TERMINAL_WAIT_MS;
  let last = null;
  while (Date.now() < deadline) {
    const r = await api('GET', `/transfers/${id}`);
    if (r.status !== 200) return r.data ? { ...last, pollError: errorText(r) } : last;
    last = r.data;
    if (last.status !== 'PENDING') return last;
    await new Promise((done) => setTimeout(done, 1000));
  }
  return last;
}
