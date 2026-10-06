import { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconAlertTriangle, IconX } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';
import { useIdentities } from '@/lib/hooks/useIdentities';

const DISMISSED_UNTIL_KEY = 'email-bind-banner-dismissed-until';
const SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;

function readDismissedUntil(): number {
  const stored = localStorage.getItem(DISMISSED_UNTIL_KEY);
  return stored ? Number(stored) : 0;
}

// Заметный призыв, не подсказка в углу настроек: от привязки почты зависит,
// сможет ли человек войти, когда кнопку Telegram спрячут по требованиям
// 406-ФЗ/199-ФЗ. Но несмываемое предупреждение — не забота, а давление:
// закрытие запоминается на 7 дней, а не насовсем — человек, который решил
// не привязывать почту сейчас, не должен видеть это каждый день бесконечно,
// но и забыть про 406-ФЗ насовсем ему тоже не дадим.
export function EmailBindBanner() {
  const { data: identities, isLoading, isError } = useIdentities();
  const [dismissedUntil, setDismissedUntil] = useState(readDismissedUntil);

  // Загрузка и ошибка — то же самое «не знаем»: банер утверждает «у тебя
  // только Telegram», а на деле мы просто не получили ответ. Молчать в
  // обоих случаях безопаснее, чем гадать.
  if (isLoading || isError || !identities) return null;
  if (identities.some((i) => i.provider === 'EMAIL')) return null;
  if (Date.now() < dismissedUntil) return null;

  const handleDismiss = () => {
    const until = Date.now() + SNOOZE_MS;
    localStorage.setItem(DISMISSED_UNTIL_KEY, String(until));
    setDismissedUntil(until);
  };

  return (
    <div className="email-bind-banner rounded-xl border border-amber-500/40 bg-amber-500/10 p-4 flex items-center justify-between gap-4">
      <div className="flex items-center gap-3 min-w-0">
        <IconAlertTriangle className="h-5 w-5 shrink-0 text-amber-600 dark:text-amber-400" />
        <div className="min-w-0">
          <p className="text-sm font-medium">Привяжи почту</p>
          <p className="text-xs text-muted-foreground">
            Сейчас у тебя только Telegram — привяжи почту как запасной способ входа.
          </p>
        </div>
      </div>
      <div className="flex items-center gap-2 shrink-0">
        <Link to="/integrations">
          <Button size="sm">Привязать</Button>
        </Link>
        <button
          type="button"
          onClick={handleDismiss}
          aria-label="Закрыть"
          className="h-8 w-8 rounded-md flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-amber-500/15 transition-colors cursor-pointer"
        >
          <IconX className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
