import assert from 'node:assert/strict';
import { test } from 'node:test';
import { reasonText, totalsByCurrency, UUID_RE } from './format.js';

test('totals are kept per currency', () => {
  const accounts = [{ currency: 'VND', balance: 100 }, { currency: 'USD', balance: 5 }, { currency: 'VND', balance: 50 }];
  assert.deepEqual(totalsByCurrency(accounts), [['VND', 150], ['USD', 5]]);
});

test('unknown failure reasons fall back to the raw code', () => {
  assert.equal(reasonText('SOMETHING_NEW'), 'SOMETHING_NEW');
  assert.equal(reasonText(null), '');
  assert.match(reasonText('INSUFFICIENT_FUNDS'), /Số dư/);
});

test('account id validation accepts UUIDs only', () => {
  assert.ok(UUID_RE.test('3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b'));
  assert.ok(!UUID_RE.test('VN1234567890'));
});
