// `assert/strict` and `node:test` need Node 15+/18+; this form runs on Node 14 under mocha.
import { strict as assert } from 'assert';
import { reasonText, totalsByCurrency, UUID_RE } from './format.js';

describe('format', () => {
  it('keeps totals per currency', () => {
    const accounts = [{ currency: 'VND', balance: 100 }, { currency: 'USD', balance: 5 }, { currency: 'VND', balance: 50 }];
    assert.deepEqual(totalsByCurrency(accounts), [['VND', 150], ['USD', 5]]);
  });

  it('falls back to the raw code for unknown failure reasons', () => {
    assert.equal(reasonText('SOMETHING_NEW'), 'SOMETHING_NEW');
    assert.equal(reasonText(null), '');
    assert.match(reasonText('INSUFFICIENT_FUNDS'), /Số dư/);
  });

  it('accepts UUIDs only as account ids', () => {
    assert.ok(UUID_RE.test('3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b'));
    assert.ok(!UUID_RE.test('VN1234567890'));
  });
});
