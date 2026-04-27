import axios from 'axios';
import { setApiToken } from './api';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

interface AuthResponse {
  token: string;
  refreshToken: string;
  userId: string;
}

declare global {
  interface Window {
    Telegram?: {
      WebApp?: {
        initData: string;
        ready: () => void;
      };
    };
  }
}

export const authenticateViaInitData = async (): Promise<AuthResponse> => {
  const initData = window.Telegram?.WebApp?.initData;

  if (!initData) {
    throw new Error('Telegram initData not available');
  }

  try {
    const response = await axios.post<AuthResponse>(
      `${API_BASE}/auth/telegram-miniapp`,
      { initData }
    );

    const { token } = response.data;
    setApiToken(token);
    localStorage.setItem('auth_token', token);
    if (response.data.refreshToken) {
      localStorage.setItem('refresh_token', response.data.refreshToken);
    }

    return response.data;
  } catch (error) {
    console.error('Authentication failed:', error);
    throw error;
  }
};

export const isTelegramWebApp = (): boolean => {
  return !!(window.Telegram?.WebApp?.initData);
};

export const authenticateAsDemoUser = async (): Promise<AuthResponse> => {
  try {
    const response = await axios.post<AuthResponse>(
      `${API_BASE}/auth/dev-token`,
      { username: 'demo_user' }
    );
    const { token } = response.data;
    setApiToken(token);
    localStorage.setItem('auth_token', token);
    if (response.data.refreshToken) {
      localStorage.setItem('refresh_token', response.data.refreshToken);
    }
    return response.data;
  } catch (error) {
    console.error('Demo authentication failed:', error);
    throw error;
  }
};

export const authenticateViaLoginWidget = async (
  widgetData: Record<string, string | number>
): Promise<AuthResponse> => {
  const fields: Record<string, string> = {};
  for (const [key, value] of Object.entries(widgetData)) {
    fields[key] = String(value);
  }
  try {
    const response = await axios.post<AuthResponse>(
      `${API_BASE}/auth/telegram-login`,
      { fields }
    );
    const { token } = response.data;
    setApiToken(token);
    localStorage.setItem('auth_token', token);
    if (response.data.refreshToken) {
      localStorage.setItem('refresh_token', response.data.refreshToken);
    }
    return response.data;
  } catch (error) {
    console.error('Login widget auth failed:', error);
    throw error;
  }
};

export const getStoredToken = (): string | null => {
  return localStorage.getItem('auth_token');
};

export const clearAuthTokens = () => {
  localStorage.removeItem('auth_token');
  localStorage.removeItem('refresh_token');
};

export const initializeTelegramWebApp = () => {
  const twa = window.Telegram?.WebApp;
  if (!twa) return;
  twa.ready();
  twa.expand();
  twa.disableVerticalSwipes?.();
};

export const getUserFromToken = (token: string): { id: string; username: string } | null => {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return { id: payload.sub ?? '', username: payload.username ?? '' };
  } catch {
    return null;
  }
};
