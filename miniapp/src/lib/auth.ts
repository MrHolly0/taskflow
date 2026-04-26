import axios from 'axios';
import { setApiToken } from './api';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';

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

export const getStoredToken = (): string | null => {
  return localStorage.getItem('auth_token');
};

export const clearAuthTokens = () => {
  localStorage.removeItem('auth_token');
  localStorage.removeItem('refresh_token');
};

export const initializeTelegramWebApp = () => {
  if (window.Telegram?.WebApp) {
    window.Telegram.WebApp.ready();
  }
};
