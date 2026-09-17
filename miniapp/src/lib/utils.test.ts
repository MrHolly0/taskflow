import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatDeadline } from './utils';

const TIMEZONE = 'Europe/Moscow';

describe('formatDeadline', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-03-15T10:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('активная задача со сроком в прошлом — «Просрочено»', () => {
    const result = formatDeadline('2026-03-10T10:00:00Z', TIMEZONE, 'TODO');
    expect(result).toContain('Просрочено');
  });

  it('выполненная задача со сроком в прошлом — без «Просрочено»', () => {
    const result = formatDeadline('2026-03-10T10:00:00Z', TIMEZONE, 'DONE');
    expect(result).not.toContain('Просрочено');
  });

  it('отменённая задача со сроком в прошлом — без «Просрочено»', () => {
    const result = formatDeadline('2026-03-10T10:00:00Z', TIMEZONE, 'CANCELLED');
    expect(result).not.toContain('Просрочено');
  });

  it('активная задача со сроком завтра — «Завтра»', () => {
    const result = formatDeadline('2026-03-16T10:00:00Z', TIMEZONE, 'TODO');
    expect(result).toBe('Завтра');
  });

  it('выполненная задача со сроком завтра — без относительной подписи «Завтра»', () => {
    const result = formatDeadline('2026-03-16T10:00:00Z', TIMEZONE, 'DONE');
    expect(result).not.toBe('Завтра');
    expect(result).not.toContain('Завтра');
  });

  it('закрытая задача показывает нейтральную дату, не относительную подпись', () => {
    const done = formatDeadline('2026-03-10T10:00:00Z', TIMEZONE, 'DONE');
    const cancelled = formatDeadline('2026-03-16T10:00:00Z', TIMEZONE, 'CANCELLED');
    expect(done).toBe('10 марта');
    expect(cancelled).toBe('16 марта');
  });
});
