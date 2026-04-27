import axios, { AxiosInstance, AxiosError } from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

interface ApiConfig {
  token?: string;
  onTokenRefresh?: (newToken: string) => void;
}

let apiConfig: ApiConfig = {};

export const createApiClient = (config: ApiConfig): AxiosInstance => {
  apiConfig = config;

  const client = axios.create({
    baseURL: API_BASE,
    timeout: 10000,
  });

  client.interceptors.request.use((config) => {
    if (apiConfig.token) {
      config.headers.Authorization = `Bearer ${apiConfig.token}`;
    }
    return config;
  });

  client.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const originalRequest = error.config as any;

      if (error.response?.status === 401 && !originalRequest._retry) {
        originalRequest._retry = true;

        try {
          const refreshResponse = await axios.post(`${API_BASE}/auth/refresh`, {
            token: apiConfig.token,
          });

          const newToken = refreshResponse.data.token;
          apiConfig.token = newToken;
          apiConfig.onTokenRefresh?.(newToken);

          originalRequest.headers.Authorization = `Bearer ${newToken}`;
          return client(originalRequest);
        } catch (refreshError) {
          return Promise.reject(refreshError);
        }
      }

      return Promise.reject(error);
    }
  );

  return client;
};

export const setApiToken = (token: string) => {
  apiConfig.token = token;
};

export const getApiClient = (): AxiosInstance => {
  return axios.create({
    baseURL: API_BASE,
    timeout: 10000,
    headers: {
      Authorization: `Bearer ${apiConfig.token}`,
    },
  });
};
