import { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconBell } from '@tabler/icons-react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/app/components/ui/dialog';
import { Button } from '@/app/components/ui/button';
import { useTasksList } from '@/lib/hooks/useTasks';
import { useUpdateSettings } from '@/lib/hooks/useSettings';
import { usePushSubscription } from '@/lib/hooks/usePushSubscription';
import { useNotificationChannelStatus } from '@/lib/hooks/useNotificationChannelStatus';

const DISMISS_KEY = 'notif-channel-prompt-dismissed';

// Каждый новый российский пользователь приземляется с нулём рабочих каналов:
// Telegram спрятан, почта выключена по умолчанию, push ещё не разрешён. Это
// основной путь, а не край, поэтому — выбор, а не баннер-предупреждение
// постфактум. Момент показа — не пустое приложение при первом входе (решать
// ещё не о чем), а первая задача со сроком, когда напоминания впервые стали
// значить что-то конкретное.
export function NotificationChannelPrompt() {
  const { ready, hasAnyWorkingChannel } = useNotificationChannelStatus();
  const { data: tasks } = useTasksList({ status: 'active', order: 'deadline', size: 1 }, ready && !hasAnyWorkingChannel);
  const updateSettings = useUpdateSettings();
  const { subscribe } = usePushSubscription();
  const [dismissed, setDismissed] = useState(() => localStorage.getItem(DISMISS_KEY) === 'true');
  const [subscribing, setSubscribing] = useState(false);

  const hasTaskWithDeadline = tasks?.some((t) => !!t.deadline) ?? false;

  if (!ready || hasAnyWorkingChannel || !hasTaskWithDeadline) {
    return null;
  }

  const resolve = () => {
    localStorage.setItem(DISMISS_KEY, 'true');
    setDismissed(true);
  };

  const handleChoosePush = async () => {
    setSubscribing(true);
    const granted = await subscribe();
    setSubscribing(false);
    if (granted) {
      updateSettings.mutate({ notifyPush: true });
      resolve();
    }
    // Отказ в разрешении — оставляем диалог открытым: закрыть его явно можно
    // крестиком, но молча считать выбор сделанным при отказе нельзя.
  };

  const handleChooseEmail = () => {
    updateSettings.mutate({ notifyEmail: true });
    resolve();
  };

  if (dismissed) {
    return (
      <div className="mb-4 flex items-center gap-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm">
        <IconBell className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
        <span className="flex-1">Напоминания сейчас никуда не приходят.</span>
        <Link to="/settings" className="font-medium underline shrink-0">
          Настроить
        </Link>
      </div>
    );
  }

  return (
    <Dialog open onOpenChange={(open) => { if (!open) resolve(); }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Куда присылать напоминания?</DialogTitle>
          <DialogDescription>
            Без этого напоминания о сроках никуда не придут.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-2 pt-2">
          <Button onClick={handleChoosePush} disabled={subscribing} className="h-11">
            Уведомления в браузере — бесплатно, рекомендуем
          </Button>
          <Button variant="outline" onClick={handleChooseEmail} className="h-11">
            На почту
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
