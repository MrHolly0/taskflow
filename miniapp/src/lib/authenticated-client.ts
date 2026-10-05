import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { useStore } from '@/lib/store';

interface TokenStorage {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
  removeItem: (key: string) => void;
}

interface ClientOptions {
  baseURL?: string;
  adapter?: AxiosRequestConfig['adapter'];
  storage?: TokenStorage;
  onAuthLost?: () => void;
}

interface RequestConfig extends InternalAxiosRequestConfig {
  authRetried?: boolean;
  accessToken?: string | null;
}

interface RefreshedTokens {
  token: string;
  refreshToken: string;
}

function expired(token: string | null | undefined): boolean {
  if (!token) return false;
  try {
    const encoded = token.split('.')[1]?.replace(/-/g, '+').replace(/_/g, '/');
    if (!encoded) return false;
    const payload: unknown = JSON.parse(atob(encoded + '='.repeat((4 - encoded.length % 4) % 4)));
    return typeof payload === 'object' && payload !== null && 'exp' in payload
      && typeof payload.exp === 'number' && payload.exp * 1000 <= Date.now();
  } catch { return false; }
}

export function createAuthenticatedClient(options: ClientOptions = {}) {
  const baseURL = options.baseURL ?? (import.meta.env.VITE_API_URL || '/api/v1');
  const storage: TokenStorage = options.storage ?? {
    getItem: key => localStorage.getItem(key),
    setItem: (key, value) => localStorage.setItem(key, value),
    removeItem: key => localStorage.removeItem(key),
  };
  const client = axios.create({ baseURL, adapter: options.adapter });
  const refreshClient = axios.create({ baseURL, adapter: options.adapter });
  let refreshing: Promise<void> | null = null;
  let invalidated = false;

  const invalidate = () => {
    if (invalidated) return;
    invalidated = true;
    storage.removeItem('auth_token');
    storage.removeItem('refresh_token');
    (options.onAuthLost ?? (() => useStore.getState().logout()))();
  };

  const refresh = async () => {
    const refreshToken = storage.getItem('refresh_token');
    if (!refreshToken) {
      invalidate();
      throw new Error('Сеанс истёк. Войди снова.');
    }
    try {
      const response = await refreshClient.post<RefreshedTokens>('/auth/refresh', { refreshToken });
      if (storage.getItem('refresh_token') !== refreshToken) {
        throw new Error('Сеанс изменился во время обновления');
      }
      if (typeof response.data.token !== 'string' || !response.data.token
        || typeof response.data.refreshToken !== 'string' || !response.data.refreshToken) {
        throw new Error('Не удалось обновить сеанс');
      }
      storage.setItem('auth_token', response.data.token);
      storage.setItem('refresh_token', response.data.refreshToken);
    } catch (error) {
      if (axios.isAxiosError(error) && [401, 403].includes(error.response?.status ?? 0)
        && storage.getItem('refresh_token') === refreshToken) invalidate();
      throw error;
    }
  };

  client.interceptors.request.use(config => {
    const request = config as RequestConfig;
    const token = storage.getItem('auth_token');
    if (token || storage.getItem('refresh_token')) invalidated = false;
    request.accessToken = token;
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
    else request.headers.delete('Authorization');
    return request;
  });

  client.interceptors.response.use(response => response, async (error: unknown) => {
    if (!axios.isAxiosError(error) || !error.config) throw error;
    const request = error.config as RequestConfig;
    const status = error.response?.status;
    const authFailure = status === 401 || (status === 403 && expired(request.accessToken));
    if (!authFailure || request.authRetried) throw error;
    request.authRetried = true;

    // Запоздавший отказ старого запроса использует уже обновлённый токен.
    if (storage.getItem('auth_token') === request.accessToken || !storage.getItem('auth_token')) {
      if (!refreshing) {
        refreshing = refresh().finally(() => { refreshing = null; });
      }
      await refreshing;
    }
    return client.request(request);
  });

  return client;
}

const authenticatedClient = createAuthenticatedClient();
export const getAuthenticatedClient = () => authenticatedClient;
