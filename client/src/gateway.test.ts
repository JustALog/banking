import { describe, expect, it } from 'vitest';
import { errorText } from './gateway';

describe('errorText', () => {
  it('reports an unreachable gateway', () => {
    expect(errorText({ status: 0, data: { error: 'fetch failed' } })).toMatch(/Không kết nối được gateway \(HTTP 0\)/);
    expect(errorText({ status: 503, data: null })).toMatch(/HTTP 503/);
  });

  it('reads validation errors, ProblemDetail and gateway errors', () => {
    expect(errorText({ status: 400, data: { errors: ['a', 'b'] } })).toBe('a; b');
    expect(errorText({ status: 422, data: { detail: 'Account not found' } })).toBe('Account not found');
    expect(errorText({ status: 400, data: { error: 'Missing or empty Idempotency-Key header' } })).toBe('Missing or empty Idempotency-Key header');
  });

  it('falls back to the status code', () => {
    expect(errorText({ status: 404, data: 'Not Found' })).toBe('HTTP 404');
  });
});
