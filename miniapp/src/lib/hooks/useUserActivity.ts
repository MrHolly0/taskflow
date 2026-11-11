import { useEffect } from 'react';
import { getAuthenticatedClient } from '@/lib/authenticated-client';

export function useUserActivity() {
  useEffect(() => {
    let nextPingAt = 0;
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
    report();
    document.addEventListener('visibilitychange', report);
    document.addEventListener('pointerdown', report, { passive: true });
    document.addEventListener('keydown', report);
    return () => {
      disposed = true;
      document.removeEventListener('visibilitychange', report);
      document.removeEventListener('pointerdown', report);
      document.removeEventListener('keydown', report);
    };
  }, []);
}
