import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAuthenticatedClient as getClient } from '@/lib/authenticated-client';
import { useStore } from '@/lib/store';

export type VoiceInputMode = 'SILENCE' | 'TOGGLE' | 'HOLD';

export interface UserSettings {
  notificationsEnabled: boolean;
  notifyTelegram: boolean;
  notifyEmail: boolean;
  notifyPush: boolean;
  taskCheckinsEnabled: boolean;
  returnNudgesEnabled: boolean;
  defaultReminderMinutes: number;
  urgentExtraReminder: boolean;
  preferredLlm: string;
  autoCleanCompletedDays: number | null;
  voiceInputModeDesktop: VoiceInputMode;
  voiceInputModeMobile: VoiceInputMode;
  timezone: string;
  displayName: string | null;
}

interface UpdateSettingsRequest {
  autoCleanCompletedDays?: number | null;
  notificationsEnabled?: boolean;
  notifyTelegram?: boolean;
  notifyEmail?: boolean;
  notifyPush?: boolean;
  taskCheckinsEnabled?: boolean;
  returnNudgesEnabled?: boolean;
  defaultReminderMinutes?: number;
  urgentExtraReminder?: boolean;
  preferredLlm?: string;
  voiceInputModeDesktop?: VoiceInputMode;
  voiceInputModeMobile?: VoiceInputMode;
  timezone?: string;
  displayName?: string;
}

export const useSettings = () => {
  return useQuery({
    queryKey: ['settings'],
    queryFn: async () => {
      const response = await getClient().get<UserSettings>('/settings');
      return response.data;
    },
    staleTime: 1000 * 60 * 5,
  });
};

// Источник истины — displayName с сервера, не имя из Zustand: то попало
// туда из JWT при входе и никогда не меняется при сохранении настроек
// (в токен правка не попадает). username из Zustand — запасной вариант на
// случай, если настройки ещё не загрузились, не основной путь. Пустая
// строка/пробелы — тот же случай, что и отсутствие имени: обрезаем.
export const useDisplayName = (): string => {
  const { data: settings } = useSettings();
  const storeUsername = useStore((s) => s.user?.username);
  const fromServer = settings?.displayName?.trim();
  if (fromServer) return fromServer;
  const fromStore = storeUsername?.trim();
  if (fromStore) return fromStore;
  return 'Друг';
};

export const useUpdateSettings = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: UpdateSettingsRequest) => {
      const response = await getClient().put<UserSettings>('/settings', request);
      return response.data;
    },
    onSuccess: (data) => {
      queryClient.setQueryData(['settings'], data);
    },
  });
};

export const useClearCompleted = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const response = await getClient().delete<{ cleared: number }>('/tasks/completed');
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
      queryClient.invalidateQueries({ queryKey: ['tasks', 'stats'] });
    },
  });
};
