import { afterEach, describe, expect, it, vi } from 'vitest';
import { receiptCountdown } from './receipt-countdown';

afterEach(() => vi.useRealTimers());
describe('время чтения результата', () => {
  it('даёт двенадцать секунд активного чтения, сохраняя остаток при паузах', () => {
    vi.useFakeTimers();
    const dismiss = vi.fn();
    const timer = receiptCountdown(12000, dismiss);
    timer.resume();
    vi.advanceTimersByTime(5000);
    timer.pause();
    vi.advanceTimersByTime(60000);
    expect(dismiss).not.toHaveBeenCalled();
    timer.resume();
    timer.resume();
    vi.advanceTimersByTime(6999);
    expect(dismiss).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(dismiss).toHaveBeenCalledTimes(1);
    timer.resume();
    vi.advanceTimersByTime(12000);
    expect(dismiss).toHaveBeenCalledTimes(1);
  });
  it('не закрывает уведомление после размонтирования', () => {
    vi.useFakeTimers();
    const dismiss = vi.fn();
    const timer = receiptCountdown(12000, dismiss);
    timer.resume();
    vi.advanceTimersByTime(1000);
    timer.dispose();
    timer.resume();
    vi.advanceTimersByTime(20000);
    expect(dismiss).not.toHaveBeenCalled();
  });
});
