import assert from 'node:assert/strict';
import { test } from 'node:test';
import { messageFor } from './gateway.ts';
import {
  formatAccountNo, groupByDay, parseAmount, reasonText, sagaSteps, validationText, statusCounts, totalsByCurrency, transferProblem,
} from './format.ts';

test('sagaSteps marks where a transfer is, or where it failed', () => {
  const states = (t: Parameters<typeof sagaSteps>[0]) => sagaSteps(t).map((s) => s.state);
  assert.deepEqual(sagaSteps({ status: 'PENDING' }).map((s) => s.label), ['Đã gửi lệnh', 'Kiểm tra an toàn', 'Ghi sổ']);
  assert.deepEqual(states({ status: 'PENDING' }), ['done', 'active', 'todo']);
  assert.deepEqual(states({ status: 'COMPLETED' }), ['done', 'done', 'done']);
  assert.deepEqual(states({ status: 'FAILED', failureReason: 'FRAUD_REJECTED' }), ['done', 'failed', 'todo']);
  assert.deepEqual(states({ status: 'FAILED', failureReason: 'INSUFFICIENT_FUNDS' }), ['done', 'done', 'failed']);
  assert.deepEqual(states({ status: 'FAILED', failureReason: 'PARTNER_TIMEOUT' }), ['done', 'done', 'failed']);
  assert.deepEqual(states({ status: 'FAILED' }), ['done', 'done', 'failed']);
});

test('totals are kept per currency', () => {
  const accounts = [{ currency: 'VND', balance: 100 }, { currency: 'USD', balance: 5 }, { currency: 'VND', balance: 50 }];
  assert.deepEqual(totalsByCurrency(accounts), [['VND', 150], ['USD', 5]]);
});

test('unknown failure reasons fall back to the raw code', () => {
  assert.equal(reasonText('SOMETHING_NEW'), 'SOMETHING_NEW');
  assert.equal(reasonText(null), '');
  assert.match(reasonText('INSUFFICIENT_FUNDS'), /Số dư/);
});

test('parseAmount accepts grouped integers only', () => {
  assert.equal(parseAmount('2500000'), 2500000);
  assert.equal(parseAmount('2.500.000'), 2500000);
  assert.equal(parseAmount('2,500,000'), 2500000);
  assert.equal(parseAmount(' 2 500 000 '), 2500000);
  assert.equal(parseAmount('0'), 0);
  for (const bad of ['', '1.5', '1,50', '-5', '25.00.000', '2.500,000', 'abc', '9007199254740993']) {
    assert.equal(parseAmount(bad), null, bad);
  }
});

test('groupByDay uses the local day and keeps order', () => {
  const now = new Date('2026-11-04T10:00:00+07:00');
  const items = [
    { id: 1, createdAt: '2026-11-04T02:00:00Z' },
    { id: 2, createdAt: '2026-11-03T18:30:00Z' }, // 01:30 on 04/11 in Vietnam
    { id: 3, createdAt: '2026-11-03T05:00:00Z' },
    { id: 4, createdAt: '2026-10-30T05:00:00Z' },
  ];
  assert.deepEqual(groupByDay(items, now).map(([l, g]) => [l, g.map((i) => i.id)]),
    [['Hôm nay', [1, 2]], ['Hôm qua', [3]], ['30/10/2026', [4]]]);
  assert.deepEqual(groupByDay([], now), []);
});

test('formatAccountNo groups by four', () => {
  assert.equal(formatAccountNo('VN03814417'), 'VN03 8144 17');
});

test('statusCounts always has every status', () => {
  assert.deepEqual(statusCounts([{ status: 'FAILED' }, { status: 'FAILED' }]), { PENDING: 0, COMPLETED: 0, FAILED: 2 });
});

test('transferProblem checks in order', () => {
  const from = { id: '3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b', accountNo: 'VN0000000001', balance: 1000, currency: 'VND' };
  const dest = 'VN0123456789';
  assert.match(transferProblem({ mode: 'other', from, dest: '', amount: 5 }), /Nhập số tài khoản/);
  assert.match(transferProblem({ mode: 'own', from, dest: '', amount: 5 }), /chưa có tài khoản khác/);
  assert.match(transferProblem({ mode: 'other', from, dest: 'VN123', amount: 5 }), /không đúng định dạng/);
  assert.match(transferProblem({ mode: 'other', from, dest: from.accountNo, amount: 5 }), /phải khác/);
  assert.match(transferProblem({ mode: 'other', from, dest, amount: null }), /số nguyên dương/);
  assert.match(transferProblem({ mode: 'other', from, dest, amount: 0 }), /số nguyên dương/);
  assert.match(transferProblem({ mode: 'other', from, dest, amount: 1001 }), /vượt số dư/);
  assert.equal(transferProblem({ mode: 'other', from, dest, amount: 1000 }), '');
});

test('validationText turns backend field errors into Vietnamese', () => {
  assert.equal(validationText([{ field: 'password', message: 'size' }]), 'Mật khẩu cần từ 8 đến 100 ký tự.');
  assert.equal(validationText([{ field: 'fullName', message: 'must not be blank' }, { field: 'email', message: 'bad' }]),
    'Nhập họ và tên. Email không đúng định dạng.');
  assert.equal(validationText([{ field: 'currency', message: 'weird' }]), 'weird');
  assert.equal(validationText(undefined), null);
});

test('messageFor prefers a known error code over the English detail', () => {
  assert.equal(messageFor(422, { code: 'CURRENCY_MISMATCH', detail: 'Currency mismatch' }), 'Hai tài khoản khác loại tiền.');
  assert.equal(messageFor(422, { code: 'UNKNOWN_CODE', detail: 'Boom' }), 'Boom');
  assert.equal(messageFor(429, { code: 'TOO_MANY_ATTEMPTS' }), 'Bạn nhập sai quá nhiều lần. Thử lại sau 15 phút.');
  assert.equal(messageFor(409, { code: 'ACCOUNT_LIMIT_REACHED' }), 'Bạn đã có tối đa 10 tài khoản.');
});
