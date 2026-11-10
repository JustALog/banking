import { describe, expect, it } from 'vitest';
import { STATUS_FILTERS, money, reasonText, shortId, totalsByCurrency, UUID_RE } from './format';

describe('format', () => {
  it('keeps totals per currency', () => {
    const accounts = [{ currency: 'VND', balance: 100 }, { currency: 'USD', balance: 5 }, { currency: 'VND', balance: 50 }];
    expect(totalsByCurrency(accounts)).toEqual([['VND', 150], ['USD', 5]]);
  });

  it('falls back to the raw code for unknown failure reasons', () => {
    expect(reasonText('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(reasonText(null)).toBe('');
    expect(reasonText('INSUFFICIENT_FUNDS')).toMatch(/Số dư/);
  });

  it('accepts UUIDs only as account ids', () => {
    expect(UUID_RE.test('3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b')).toBe(true);
    expect(UUID_RE.test('VN1234567890')).toBe(false);
  });

  it('shortens ids and tolerates missing ones', () => {
    expect(shortId('3f2b8c1e-9a4d-4e2f-8b1a-0c9d8e7f6a5b')).toBe('3f2b8c1e…6a5b');
    expect(shortId(undefined)).toBe('');
  });

  it('prints whole minor units with the currency', () => {
    expect(money(0, 'USD')).toBe('0 USD');
    expect(money(250000)).toMatch(/^250.000 VND$/);
  });

  it('offers "all" plus every transfer status as filters', () => {
    expect(STATUS_FILTERS.map(([k]) => k)).toEqual(['', 'PENDING', 'COMPLETED', 'FAILED']);
  });
});
