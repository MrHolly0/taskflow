import { useTheme } from 'next-themes';
import { Card } from '@/app/components/ui/card';
import { Label } from '@/app/components/ui/label';
import { Switch } from '@/app/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { Button } from '@/app/components/ui/button';
import { Separator } from '@/app/components/ui/separator';
import { useStore } from '@/lib/store';

function useSetting(key: string, defaultValue: string): [string, (v: string) => void] {
  const stored = localStorage.getItem(key) ?? defaultValue;
  const set = (v: string) => localStorage.setItem(key, v);
  return [stored, set];
}

function useBoolSetting(key: string, defaultValue: boolean): [boolean, (v: boolean) => void] {
  const stored = localStorage.getItem(key);
  const value = stored === null ? defaultValue : stored === 'true';
  const set = (v: boolean) => localStorage.setItem(key, String(v));
  return [value, set];
}

export function SettingsPage() {
  const { theme, setTheme } = useTheme();
  const user = useStore((s) => s.user);
  const logout = useStore((s) => s.logout);

  const [timezone, setTimezone] = useSetting('settings.timezone', 'europe-moscow');
  const [notifications, setNotifications] = useBoolSetting('settings.notifications', true);
  const [reminderTime, setReminderTime] = useSetting('settings.reminderTime', '1h');
  const [urgentExtra, setUrgentExtra] = useBoolSetting('settings.urgentExtra', true);

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <h1 className="text-2xl font-semibold">Настройки</h1>

      <Card className="p-6 flex flex-col gap-8">
        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Профиль</h2>
          {user ? (
            <div className="flex items-center gap-3 p-3 rounded-xl bg-muted/50">
              <div className="w-10 h-10 rounded-full bg-primary/20 flex items-center justify-center text-sm font-semibold text-primary flex-shrink-0">
                {(user.name || user.username || '?').charAt(0).toUpperCase()}
              </div>
              <div className="flex flex-col gap-0.5">
                <p className="font-medium text-sm leading-tight">{user.name || user.username}</p>
                {user.username && user.username !== user.name && (
                  <p className="text-xs text-muted-foreground leading-tight">@{user.username} · Telegram</p>
                )}
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">Профиль не загружен</p>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor="timezone">Часовой пояс</Label>
            <Select value={timezone} onValueChange={setTimezone}>
              <SelectTrigger id="timezone" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="europe-moscow">Europe/Moscow (UTC+3)</SelectItem>
                <SelectItem value="europe-kaliningrad">Europe/Kaliningrad (UTC+2)</SelectItem>
                <SelectItem value="asia-yekaterinburg">Asia/Yekaterinburg (UTC+5)</SelectItem>
                <SelectItem value="asia-novosibirsk">Asia/Novosibirsk (UTC+7)</SelectItem>
                <SelectItem value="asia-vladivostok">Asia/Vladivostok (UTC+10)</SelectItem>
                <SelectItem value="utc">UTC</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-5">
          <h2 className="text-base font-semibold">Напоминания</h2>
          <div className="flex items-center justify-between gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="notifications">Включить напоминания</Label>
              <p className="text-xs text-muted-foreground">
                Уведомления о задачах с дедлайнами
              </p>
            </div>
            <Switch
              id="notifications"
              checked={notifications}
              onCheckedChange={setNotifications}
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="reminder-time">Время напоминания</Label>
            <Select value={reminderTime} onValueChange={setReminderTime} disabled={!notifications}>
              <SelectTrigger id="reminder-time" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="15m">За 15 минут</SelectItem>
                <SelectItem value="30m">За 30 минут</SelectItem>
                <SelectItem value="1h">За 1 час</SelectItem>
                <SelectItem value="2h">За 2 часа</SelectItem>
                <SelectItem value="1d">За день</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center justify-between gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="urgent-reminder">Доп. за 15 мин для срочных</Label>
              <p className="text-xs text-muted-foreground">
                Дополнительное напоминание для срочных задач
              </p>
            </div>
            <Switch
              id="urgent-reminder"
              checked={urgentExtra}
              onCheckedChange={setUrgentExtra}
              disabled={!notifications}
            />
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">AI-разбор</h2>
          <div className="flex flex-col gap-2">
            <p className="text-sm text-muted-foreground">
              Используется <span className="font-medium text-foreground">Groq (llama3)</span> с автоматическим переключением на YandexGPT при недоступности. Настраивается в переменных окружения сервера.
            </p>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Внешний вид</h2>
          <div className="flex flex-col gap-2">
            <Label>Тема</Label>
            <div className="flex gap-2">
              <Button
                variant={theme === 'light' ? 'default' : 'outline'}
                onClick={() => setTheme('light')}
                className="flex-1 h-10"
              >
                Светлая
              </Button>
              <Button
                variant={theme === 'dark' ? 'default' : 'outline'}
                onClick={() => setTheme('dark')}
                className="flex-1 h-10"
              >
                Тёмная
              </Button>
              <Button
                variant={theme === 'system' ? 'default' : 'outline'}
                onClick={() => setTheme('system')}
                className="flex-1 h-10"
              >
                Системная
              </Button>
            </div>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Аккаунт</h2>
          <Button variant="destructive" onClick={logout} className="h-10 self-start">
            Выйти из аккаунта
          </Button>
        </section>
      </Card>
    </div>
  );
}
