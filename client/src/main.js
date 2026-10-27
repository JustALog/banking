import { api, errorText, hooks, log, setToken, waitForTransfer } from './api.js';
import { scenarios } from './scenarios.js';

const app = document.getElementById('app');
const state = { customer: null, tab: 'accounts', results: {}, running: false };

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (k === 'value') node.value = v;
    else if (v === true) node.setAttribute(k, '');
    else if (v !== false && v != null) node.setAttribute(k, v);
  }
  node.append(...children.flat().filter((c) => c != null));
  return node;
}

const money = (n, currency = '') => `${Number(n).toLocaleString('vi-VN')} ${currency}`.trim();
const time = (iso) => new Date(iso).toLocaleString('vi-VN');
const message = () => el('p', { class: 'msg', role: 'status' });
const say = (node, text, cls = '') => { node.textContent = text; node.className = `msg ${cls}`; };
const field = (label, input) => el('label', {}, label, input);

function table(headers, rows, empty) {
  if (!rows.length) return el('p', { class: 'muted' }, empty);
  return el('div', { class: 'table' }, el('table', {},
    el('thead', {}, el('tr', {}, headers.map((h) => el('th', { scope: 'col' }, h)))),
    el('tbody', {}, rows.map((cells) => el('tr', {}, cells.map((c) => (c?.tagName === 'TD' ? c : el('td', {}, c))))))));
}

// Runs an async button action without allowing a second click while it is in flight.
function busy(button, fn) {
  return async (event) => {
    event?.preventDefault();
    button.disabled = true;
    try { await fn(); } finally { button.disabled = false; }
  };
}

/* ---------- Auth ---------- */

function authView() {
  const loginMsg = message();
  const regMsg = message();

  const email = () => el('input', { type: 'email', name: 'email', required: true, autocomplete: 'username' });
  const signIn = async (credentials, msg) => {
    const r = await api('POST', '/auth/login', { body: credentials });
    if (r.status !== 200) return say(msg, errorText(r), 'fail');
    setToken(r.data.accessToken);
    state.customer = r.data.customer;
    return render();
  };

  const loginBtn = el('button', { type: 'submit' }, 'Đăng nhập');
  const loginForm = el('form', {},
    field('Email', email()),
    field('Mật khẩu', el('input', { type: 'password', name: 'password', required: true, autocomplete: 'current-password' })),
    el('div', {}, loginBtn), loginMsg);
  loginForm.addEventListener('submit', busy(loginBtn, () => {
    say(loginMsg, '');
    return signIn(Object.fromEntries(new FormData(loginForm)), loginMsg);
  }));

  const regBtn = el('button', { type: 'submit' }, 'Đăng ký và đăng nhập');
  const regForm = el('form', {},
    field('Họ tên', el('input', { name: 'fullName', required: true, maxlength: 255, autocomplete: 'name' })),
    field('Email', email()),
    field('Mật khẩu (ít nhất 8 ký tự)', el('input', { type: 'password', name: 'password', required: true, minlength: 8, maxlength: 100, autocomplete: 'new-password' })),
    el('div', {}, regBtn), regMsg);
  regForm.addEventListener('submit', busy(regBtn, async () => {
    say(regMsg, '');
    const data = Object.fromEntries(new FormData(regForm));
    const r = await api('POST', '/auth/register', { body: data });
    if (r.status !== 201) return say(regMsg, errorText(r), 'fail');
    return signIn({ email: data.email, password: data.password }, regMsg);
  }));

  return el('div', { class: 'auth' },
    el('h2', {}, 'Bảng kiểm thử hệ thống ngân hàng'),
    el('p', {}, 'Đăng nhập để mở tài khoản, chuyển tiền và chạy các kịch bản kiểm thử.'),
    el('div', { class: 'grid2' },
      el('section', {}, el('h3', {}, 'Đăng nhập'), loginForm),
      el('section', {}, el('h3', {}, 'Tạo người dùng mới'), regForm)));
}

/* ---------- Accounts ---------- */

const seedCommand = (id) => `cd server && docker compose -f docker/docker-compose.yml exec -T postgres \\
  psql -U postgres -d ledger_db -c "UPDATE account SET balance = 10000000 WHERE id = '${id}';"`;

function accountsView() {
  const list = el('div');
  const detail = el('div');
  const msg = message();

  async function showEntries(account) {
    const r = await api('GET', `/accounts/${account.id}/entries`);
    if (r.status !== 200) return detail.replaceChildren(el('p', { class: 'msg fail' }, errorText(r)));
    return detail.replaceChildren(el('section', {},
      el('h3', {}, `Bút toán của ${account.accountNo}`),
      table(['Thời gian', 'Chiều', 'Số tiền', 'Mã giao dịch'],
        r.data.map((e) => [time(e.createdAt), e.direction, el('td', { class: 'num' }, money(e.amount, account.currency)), el('span', { class: 'id' }, e.transferId)]),
        'Tài khoản chưa có bút toán.'),
      el('h3', { style: 'margin-top:20px' }, 'Nạp tiền thử'),
      el('p', { class: 'muted' }, 'Hệ thống không có API nạp tiền. Chạy lệnh sau ở thư mục gốc của repo, rồi bấm Tải lại.'),
      el('pre', { class: 'cmd' }, seedCommand(account.id))));
  }

  async function load() {
    const r = await api('GET', '/accounts');
    if (r.status !== 200) return list.replaceChildren(el('p', { class: 'msg fail' }, errorText(r)));
    return list.replaceChildren(table(['Số tài khoản', 'Số dư', 'Mã tài khoản', ''],
      r.data.map((a) => {
        const view = el('button', { class: 'quiet', type: 'button' }, 'Xem bút toán');
        view.addEventListener('click', busy(view, () => showEntries(a)));
        return [a.accountNo, el('td', { class: 'num' }, money(a.balance, a.currency)), el('span', { class: 'id' }, a.id), view];
      }),
      'Chưa có tài khoản. Mở tài khoản đầu tiên ở trên.'));
  }

  const currency = el('input', { name: 'currency', value: 'VND', required: true, pattern: '[A-Z]{3}', maxlength: 3, 'aria-label': 'Loại tiền' });
  currency.style.width = '96px';
  const openBtn = el('button', { type: 'submit' }, 'Mở tài khoản');
  const form = el('form', {}, el('div', { class: 'row' }, currency, openBtn), msg);
  form.addEventListener('submit', busy(openBtn, async () => {
    say(msg, '');
    const r = await api('POST', '/accounts', { body: { currency: currency.value } });
    if (r.status !== 201) return say(msg, errorText(r), 'fail');
    say(msg, `Đã mở tài khoản ${r.data.accountNo}.`, 'pass');
    return load();
  }));

  const reload = el('button', { class: 'quiet', type: 'button' }, 'Tải lại');
  reload.addEventListener('click', busy(reload, load));
  load();

  return el('div', {},
    el('h2', {}, 'Tài khoản'),
    el('section', {}, form),
    el('section', {}, el('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:12px' }, el('h3', { style: 'margin:0' }, 'Tài khoản của tôi'), reload), list),
    detail);
}

/* ---------- Transfers ---------- */

function statusCell(t) {
  return el('span', { class: `status ${t.status}` }, t.failureReason ? `${t.status} / ${t.failureReason}` : t.status);
}

function transfersView() {
  const list = el('div');
  const msg = message();
  const source = el('select', { name: 'source', required: true });
  const options = el('datalist', { id: 'own-accounts' });
  const dest = el('input', { name: 'dest', required: true, list: 'own-accounts', placeholder: 'Mã tài khoản (UUID)' });
  const amount = el('input', { name: 'amount', type: 'number', min: 1, step: 1, max: Number.MAX_SAFE_INTEGER, required: true, value: '250000' });
  const key = el('input', { name: 'key', required: true, value: crypto.randomUUID() });
  let accounts = [];

  async function loadAccounts() {
    const r = await api('GET', '/accounts');
    if (r.status !== 200) return say(msg, errorText(r), 'fail');
    accounts = r.data;
    source.replaceChildren(...accounts.map((a) => el('option', { value: a.id }, `${a.accountNo}: ${money(a.balance, a.currency)}`)));
    options.replaceChildren(...accounts.map((a) => el('option', { value: a.id }, a.accountNo)));
    return undefined;
  }

  async function loadList() {
    const r = await api('GET', '/transfers');
    if (r.status !== 200) return list.replaceChildren(el('p', { class: 'msg fail' }, errorText(r)));
    return list.replaceChildren(table(['Thời gian', 'Số tiền', 'Trạng thái', 'Mã giao dịch'],
      r.data.map((t) => [time(t.createdAt), el('td', { class: 'num' }, money(t.amount, t.currency)), statusCell(t), el('span', { class: 'id' }, t.id)]),
      'Chưa có giao dịch.'));
  }

  const submit = el('button', { type: 'submit' }, 'Chuyển tiền');
  const form = el('form', {},
    field('Từ tài khoản', source),
    field('Đến tài khoản', dest), options,
    field('Số tiền', amount),
    field('Idempotency-Key (giữ nguyên để thử gửi lại)', key),
    el('div', {}, submit), msg);

  form.addEventListener('submit', busy(submit, async () => {
    say(msg, '');
    const from = accounts.find((a) => a.id === source.value);
    if (!from) return say(msg, 'Hãy mở tài khoản trước khi chuyển tiền.', 'fail');
    const r = await api('POST', '/transfers', {
      body: { sourceAccountId: from.id, destAccountId: dest.value.trim(), amount: Number(amount.value), currency: from.currency },
      headers: { 'Idempotency-Key': key.value },
    });
    // On failure the key stays, so resending cannot create a second transfer.
    if (r.status !== 202 && r.status !== 200) return say(msg, errorText(r), 'fail');
    const replay = r.status === 200 ? 'Gửi lại, trả về giao dịch cũ. ' : '';
    say(msg, `${replay}Đang xử lý giao dịch ${r.data.id}...`, 'running');
    await loadList();
    const done = await waitForTransfer(r.data.id);
    if (!done || done.status === 'PENDING') {
      say(msg, 'Sau 60 giây giao dịch vẫn PENDING. Kiểm tra Debezium connector và Kafka.', 'running');
    } else {
      say(msg, `Kết quả: ${done.status}${done.failureReason ? ` / ${done.failureReason}` : ''}`, done.status === 'COMPLETED' ? 'pass' : 'fail');
      key.value = crypto.randomUUID();
    }
    await Promise.all([loadList(), loadAccounts()]);
    return undefined;
  }));

  const reload = el('button', { class: 'quiet', type: 'button' }, 'Tải lại');
  reload.addEventListener('click', busy(reload, loadList));
  loadAccounts();
  loadList();

  return el('div', {},
    el('h2', {}, 'Chuyển tiền'),
    el('section', {}, form),
    el('section', {}, el('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:12px' }, el('h3', { style: 'margin:0' }, 'Lịch sử giao dịch'), reload), list));
}

/* ---------- Notifications ---------- */

function notificationsView() {
  const list = el('div');
  async function load() {
    const r = await api('GET', '/notifications');
    if (r.status !== 200) return list.replaceChildren(el('p', { class: 'msg fail' }, errorText(r)));
    return list.replaceChildren(table(['Thời gian', 'Tiêu đề', 'Nội dung', 'Kênh'],
      r.data.map((n) => [time(n.createdAt), n.title, n.content, n.channel]),
      'Chưa có thông báo. Thông báo xuất hiện sau khi giao dịch kết thúc.'));
  }
  const reload = el('button', { class: 'quiet', type: 'button' }, 'Tải lại');
  reload.addEventListener('click', busy(reload, load));
  load();
  return el('div', {},
    el('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:16px' }, el('h2', { style: 'margin:0' }, 'Thông báo'), reload),
    el('section', {}, list),
    el('p', { class: 'muted' }, 'Email và SMS đang được giả lập: hệ thống chỉ ghi log và lưu bản ghi, không gửi thật.'));
}

/* ---------- Scenarios ---------- */

const PARTNER_SLOW = `cd server && BANKING_PARTNER_EXECUTE_PATH=/partner-slow/v1/transfers \\
  docker compose -f docker/docker-compose.yml up -d transfer-service`;
const PARTNER_RESTORE = 'cd server && docker compose -f docker/docker-compose.yml up -d transfer-service';

function scenariosView() {
  const body = el('tbody');
  const runAll = el('button', { type: 'button' }, 'Chạy tất cả');

  async function run(s) {
    state.results[s.name] = { running: true };
    paint();
    try {
      state.results[s.name] = await s.run();
    } catch (e) {
      state.results[s.name] = { pass: false, actual: e.message };
    }
    paint();
  }

  async function guarded(list) {
    state.running = true;
    paint();
    try {
      for (const s of list) await run(s);
    } finally {
      state.running = false;
      paint();
    }
  }

  function paint() {
    runAll.disabled = state.running;
    body.replaceChildren(...scenarios.map((s) => {
      const res = state.results[s.name];
      let outcome = el('span', { class: 'muted' }, 'Chưa chạy');
      if (res?.running) outcome = el('span', { class: 'status running' }, 'Đang chạy...');
      else if (res) {
        outcome = el('span', {},
          el('span', { class: `status ${res.pass ? 'pass' : 'fail'}` }, res.pass ? 'Đạt' : 'Không đạt'),
          el('br'), res.actual);
      }
      const button = el('button', { class: 'quiet', type: 'button', disabled: state.running, onclick: () => guarded([s]) }, 'Chạy');
      return el('tr', {}, el('td', {}, s.name), el('td', {}, s.expected), el('td', {}, outcome), el('td', {}, button));
    }));
  }

  runAll.addEventListener('click', () => guarded(scenarios));
  paint();

  return el('div', {},
    el('div', { class: 'row', style: 'justify-content:space-between;margin-bottom:16px' }, el('h2', { style: 'margin:0' }, 'Kịch bản kiểm thử'), runAll),
    el('section', {},
      el('p', { class: 'muted' }, 'Mỗi kịch bản tự mở tài khoản rỗng riêng nên không cần nạp tiền. Xem từng request ở cột bên phải.'),
      el('div', { class: 'table' }, el('table', {},
        el('thead', {}, el('tr', {}, ['Kịch bản', 'Kết quả mong đợi', 'Kết quả thực tế', ''].map((h) => el('th', { scope: 'col' }, h)))),
        body))),
    el('section', {},
      el('h3', {}, 'Partner timeout và đảo bút toán (làm tay)'),
      el('p', {}, 'Kịch bản này phải khởi động lại transfer-service để trỏ sang đường dẫn partner chậm 5 giây.'),
      el('pre', { class: 'cmd' }, PARTNER_SLOW),
      el('p', { style: 'margin-top:12px' }, 'Nạp tiền cho một tài khoản rồi chuyển tiền ở tab Chuyển tiền. Mong đợi: FAILED / PARTNER_TIMEOUT, số dư hai bên trở về như cũ và tab Tài khoản có thêm bút toán đảo.'),
      el('p', {}, 'Chạy lệnh sau để trở lại bình thường:'),
      el('pre', { class: 'cmd' }, PARTNER_RESTORE)));
}

/* ---------- Shell ---------- */

const TABS = {
  accounts: ['Tài khoản', accountsView],
  transfers: ['Chuyển tiền', transfersView],
  notifications: ['Thông báo', notificationsView],
  scenarios: ['Kịch bản kiểm thử', scenariosView],
};

const logList = el('div', { class: 'list', 'aria-live': 'off' });

function paintLog() {
  logList.replaceChildren(...log.map((entry) => {
    const good = entry.status >= 200 && entry.status < 300;
    return el('details', {},
      el('summary', {}, el('span', { class: `code ${good ? 'good' : 'fail'}` }, String(entry.status)), ` ${entry.method} ${entry.path} (${entry.ms} ms)`),
      entry.headers ? el('pre', {}, entry.headers) : null,
      el('pre', {}, entry.body ?? '(không có nội dung)'));
  }));
}

function logout() {
  setToken(null);
  state.customer = null;
  state.results = {};
  render();
}

function render() {
  const logPanel = el('aside', { 'aria-label': 'Nhật ký API' }, el('h3', {}, 'Nhật ký API (qua gateway)'), logList);
  paintLog();
  if (!state.customer) {
    app.replaceChildren(el('div', { class: 'layout', style: 'min-height:100vh' }, authView(), logPanel));
    return;
  }
  const tabs = Object.entries(TABS).map(([id, [label]]) => el('button', {
    type: 'button',
    'aria-current': state.tab === id ? 'page' : false,
    onclick: () => { state.tab = id; render(); },
  }, label));
  app.replaceChildren(
    el('nav', { 'aria-label': 'Chính' }, el('strong', {}, 'Banking'), tabs,
      el('span', { class: 'who' }, `${state.customer.fullName} (${state.customer.email})`),
      el('button', { type: 'button', onclick: logout }, 'Đăng xuất')),
    el('div', { class: 'layout' }, el('main', {}, TABS[state.tab][1]()), logPanel));
}

hooks({
  onLog: paintLog,
  onExpired: () => { if (state.customer) logout(); },
});
render();
