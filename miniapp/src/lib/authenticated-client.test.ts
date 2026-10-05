import { describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { createAuthenticatedClient } from './authenticated-client';

const jwt = (expiresIn: number) => `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expiresIn }))}.signature`;

function tokens(access = jwt(-30), refresh = 'refresh-before') {
  const values = new Map([['auth_token', access], ['refresh_token', refresh]]);
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
  };
}

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { data, status, statusText: String(status), config, headers: new AxiosHeaders() };
}

function refuse(config: InternalAxiosRequestConfig, status: number): never {
  throw new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, response(config, {}, status));
}

describe('обновление сеанса', () => {
  it('один refresh обслуживает параллельные запросы и вращает оба токена', async () => {
    const storage = tokens();
    const access = jwt(900);
    let release = () => {};
    const gate = new Promise<void>(resolve => { release = resolve; });
    const refreshCalls = vi.fn();
    const retried: string[] = [];
    const message = new FormData();
    message.append('text', 'Документы отправлены');
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') {
        refreshCalls();
        expect(JSON.parse(String(config.data))).toEqual({ refreshToken: 'refresh-before' });
        expect(config.headers.get('Authorization')).toBeUndefined();
        await gate;
        return response(config, { token: access, refreshToken: 'refresh-after' });
      }
      if (config.url === '/assistant/messages') {
        expect(config.data).toBe(message);
        expect(config.data.get('text')).toBe('Документы отправлены');
      }
      if (config.headers.get('Authorization') !== `Bearer ${access}`) refuse(config, 401);
      retried.push(String(config.url));
      return response(config, { ok: true });
    };
    const client = createAuthenticatedClient({ storage, adapter });
    const requests = Promise.all([client.get('/tasks'), client.get('/settings'), client.post('/assistant/messages', message)]);
    await vi.waitFor(() => expect(refreshCalls).toHaveBeenCalledTimes(1));
    release();
    await requests;
    expect(refreshCalls).toHaveBeenCalledTimes(1);
    expect(retried).toHaveLength(3);
    expect(storage.getItem('auth_token')).toBe(access);
    expect(storage.getItem('refresh_token')).toBe('refresh-after');
  });

  it('обновляет истёкший JWT при 403, но не разлогинивает при отказе доступа', async () => {
    const storage = tokens();
    const access = jwt(900);
    const refreshCalls = vi.fn();
    const onAuthLost = vi.fn();
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') {
        refreshCalls();
        return response(config, { token: access, refreshToken: 'rotated' });
      }
      if (config.headers.get('Authorization') !== `Bearer ${access}`) refuse(config, 403);
      if (config.url === '/forbidden') refuse(config, 403);
      return response(config, 'ok');
    };
    const client = createAuthenticatedClient({ storage, adapter, onAuthLost });
    await expect(client.get('/tasks')).resolves.toMatchObject({ data: 'ok' });
    await expect(client.get('/forbidden')).rejects.toMatchObject({ response: { status: 403 } });
    expect(refreshCalls).toHaveBeenCalledTimes(1);
    expect(onAuthLost).not.toHaveBeenCalled();
    expect(storage.getItem('auth_token')).toBe(access);
  });

  it('не повторяет refresh после единственной неудачной попытки повторить запрос', async () => {
    const storage = tokens();
    const refreshCalls = vi.fn();
    const requests = vi.fn();
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') {
        refreshCalls();
        return response(config, { token: jwt(900), refreshToken: 'rotated' });
      }
      requests();
      refuse(config, 401);
    };
    const client = createAuthenticatedClient({ storage, adapter });
    await expect(client.get('/tasks')).rejects.toMatchObject({ response: { status: 401 } });
    expect(requests).toHaveBeenCalledTimes(2);
    expect(refreshCalls).toHaveBeenCalledTimes(1);
  });

  it('при сетевой ошибке refresh сохраняет сеанс и позволяет повторить запрос позже', async () => {
    const original = jwt(-30);
    const storage = tokens(original);
    const onAuthLost = vi.fn();
    let online = false;
    const access = jwt(900);
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') {
        if (!online) throw new AxiosError('Network Error', 'ERR_NETWORK', config);
        return response(config, { token: access, refreshToken: 'rotated' });
      }
      if (config.headers.get('Authorization') !== `Bearer ${access}`) refuse(config, 401);
      return response(config, 'ok');
    };
    const client = createAuthenticatedClient({ storage, adapter, onAuthLost });
    await expect(client.post('/tasks/quick', { title: 'Документы' })).rejects.toMatchObject({ code: 'ERR_NETWORK' });
    expect(storage.getItem('auth_token')).toBe(original);
    expect(storage.getItem('refresh_token')).toBe('refresh-before');
    expect(onAuthLost).not.toHaveBeenCalled();
    online = true;
    await expect(client.post('/tasks/quick', { title: 'Документы' })).resolves.toMatchObject({ data: 'ok' });
  });

  it.each([401, 403])('при невалидном refresh (%s) сбрасывает сеанс ровно один раз', async status => {
    const storage = tokens();
    const onAuthLost = vi.fn();
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') refuse(config, status);
      refuse(config, 401);
    };
    const client = createAuthenticatedClient({ storage, adapter, onAuthLost });
    await Promise.allSettled([client.get('/tasks'), client.get('/settings')]);
    expect(onAuthLost).toHaveBeenCalledTimes(1);
    expect(storage.getItem('auth_token')).toBeNull();
    expect(storage.getItem('refresh_token')).toBeNull();
    await expect(client.get('/tasks')).rejects.toThrow();
    expect(onAuthLost).toHaveBeenCalledTimes(1);
  });

  it('запоздавший 401 старого запроса использует уже обновлённый токен', async () => {
    const storage = tokens();
    const access = jwt(900);
    const refreshCalls = vi.fn();
    let release = () => {};
    const gate = new Promise<void>(resolve => { release = resolve; });
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') {
        refreshCalls();
        return response(config, { token: access, refreshToken: 'rotated' });
      }
      if (config.headers.get('Authorization') !== `Bearer ${access}`) {
        if (config.url === '/late') await gate;
        refuse(config, 401);
      }
      return response(config, 'ok');
    };
    const client = createAuthenticatedClient({ storage, adapter });
    const late = client.get('/late');
    await client.get('/tasks');
    release();
    await expect(late).resolves.toMatchObject({ data: 'ok' });
    expect(refreshCalls).toHaveBeenCalledTimes(1);
  });

  it('не возвращает токены, если человек вышел во время обновления', async () => {
    const storage = tokens();
    const refreshStarted = vi.fn();
    let release = () => {};
    const gate = new Promise<void>(resolve => { release = resolve; });
    const adapter: AxiosAdapter = async config => {
      if (config.url === '/auth/refresh') {
        refreshStarted();
        await gate;
        return response(config, { token: jwt(900), refreshToken: 'rotated' });
      }
      refuse(config, 401);
    };
    const client = createAuthenticatedClient({ storage, adapter });
    const request = client.get('/tasks');
    await vi.waitFor(() => expect(refreshStarted).toHaveBeenCalledOnce());
    storage.removeItem('auth_token');
    storage.removeItem('refresh_token');
    release();
    await expect(request).rejects.toThrow('Сеанс изменился');
    expect(storage.getItem('auth_token')).toBeNull();
    expect(storage.getItem('refresh_token')).toBeNull();
  });
});
