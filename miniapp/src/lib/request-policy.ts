import axios from 'axios';

export function shouldRetryQuery(failures: number, error: unknown): boolean {
  if (failures >= 2 || axios.isCancel(error)) return false;
  if (!axios.isAxiosError(error)) return false;
  const status = error.response?.status;
  return status === undefined || status === 408 || status === 429 || status >= 500;
}

export function queryRetryDelay(attempt: number, error: unknown): number {
  const value: unknown = axios.isAxiosError(error) ? error.response?.headers?.['retry-after'] : undefined;
  if (typeof value === 'string' || typeof value === 'number') {
    const seconds = Number(value);
    const delay = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(String(value)) - Date.now();
    if (Number.isFinite(delay) && delay >= 0) return Math.max(1000, delay) + Math.random() * 1000;
  }
  const ceiling = Math.min(1000 * 2 ** attempt, 10000);
  return ceiling / 2 + Math.random() * ceiling / 2;
}
