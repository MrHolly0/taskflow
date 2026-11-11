import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { getAuthenticatedClient } from '@/lib/authenticated-client';

export function useUserActivity() {
  const queryClient = useQueryClient();
  useEffect(() => {
    let nextPingAt = 0;
    let nextRefreshAt = 0;
    let pending = false;
    let disposed = false;
    const report = () => {
      if (document.visibilityState !== 'visible' || pending || Date.now() < nextPingAt) return;
      pending = true;
      // Ошибка учёта активности не блокирует работу приложения и не запускает повтор по таймеру.
      void getAuthenticatedClient().post('/settings/activity').then(() => {
        nextPingAt = Date.now() + 5 * 60_000;
      }).catch(() => {
        nextPingAt = Date.now() + 60_000;
      }).finally(() => { if (!disposed) pending = false; });
    };
    const resume = () => {
      if (document.visibilityState !== 'visible') return;
      report();
      if (Date.now() < nextRefreshAt) return;
      nextRefreshAt = Date.now() + 15_000;
      // Кнопка в Telegram могла изменить задачу или отключить приглашения, пока страница была в фоне.
      for (const key of ['tasks', 'groups', 'settings', 'identities']) {
        void queryClient.invalidateQueries({ queryKey: [key], refetchType: 'active' });
      }
    };
    report();
    document.addEventListener('visibilitychange', resume);
    window.addEventListener('focus', resume);
    document.addEventListener('pointerdown', report, { passive: true });
    document.addEventListener('keydown', report);
    return () => {
      disposed = true;
      document.removeEventListener('visibilitychange', resume);
      window.removeEventListener('focus', resume);
      document.removeEventListener('pointerdown', report);
      document.removeEventListener('keydown', report);
    };
  }, [queryClient]);
}
