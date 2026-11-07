import { describe, expect, it } from 'vitest';
import { ApiError, errorMessage, messageFor } from './gateway';

describe('gateway error messages', () => {
  it('treats no response and gateway errors as a connection problem', () => {
    for (const status of [0, 502, 503, 504]) expect(messageFor(status)).toMatch(/Không kết nối/);
    expect(messageFor(500, null)).toMatch(/Không kết nối/);
  });

  it('prefers validation errors, then ProblemDetail, then the gateway error', () => {
    expect(messageFor(400, { errors: ['email: must not be blank', 'password: too short'] })).toBe('email: must not be blank; password: too short');
    expect(messageFor(422, { detail: 'Account not found', error: 'x' })).toBe('Account not found');
    expect(messageFor(400, { error: 'Missing or empty Idempotency-Key header' })).toBe('Missing or empty Idempotency-Key header');
  });

  it('falls back to the status for bodies it cannot read', () => {
    expect(messageFor(404, 'Not Found')).toBe('Yêu cầu thất bại (HTTP 404).');
    expect(messageFor(500, { unexpected: true })).toBe('Yêu cầu thất bại (HTTP 500).');
  });

  it('turns anything caught into display text', () => {
    const err = new ApiError(409, 'conflict', { detail: 'conflict' });
    expect(err).toBeInstanceOf(Error);
    expect(err.status).toBe(409);
    expect(errorMessage(err)).toBe('conflict');
    expect(errorMessage('boom')).toBe('boom');
  });
});
