import { describe, expect, it, vi, afterEach } from 'vitest';
import { AxiosError, AxiosHeaders, CanceledError } from 'axios';
import { shouldRetryQuery, queryRetryDelay } from './request-policy';

const failure = (status: number, retryAfter?: string) => new AxiosError('failed', undefined, undefined, undefined, {
  status, statusText: '', headers: { 'retry-after': retryAfter }, data: null,
  config: { headers: new AxiosHeaders() },
});
afterEach(() => vi.restoreAllMocks());
describe('повторы чтения', () => {
  it('не повторяет валидацию и авторизацию', () => {
    for (const status of [400, 401, 403, 404, 409, 422]) expect(shouldRetryQuery(0, failure(status))).toBe(false);
    expect(shouldRetryQuery(0, new CanceledError())).toBe(false);
  });
  it('повторяет временные ошибки ограниченное число раз', () => {
    for (const status of [408, 429, 500, 503]) expect(shouldRetryQuery(1, failure(status))).toBe(true);
    expect(shouldRetryQuery(2, failure(503))).toBe(false);
  });
  it('выдерживает Retry-After и добавляет разброс', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    expect(queryRetryDelay(0, failure(429, '30'))).toBe(30500);
    expect(queryRetryDelay(0, failure(503))).toBe(750);
    expect(queryRetryDelay(2, failure(503))).toBe(3000);
  });
});
