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

export function SettingsPage() {
  const { theme, setTheme } = useTheme();
  const user = useStore((s) => s.user);
  const logout = useStore((s) => s.logout);

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <h1 className="text-2xl font-semibold">Настройки</h1>

      <Card className="p-6 flex flex-col gap-8">
        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Профиль</h2>
          {user && (
            <div className="flex items-center gap-3 p-3 rounded-xl bg-muted/50">
              <div className="w-10 h-10 rounded-full bg-primary/20 flex items-center justify-center text-sm font-semibold text-primary flex-shrink-0">
                {user.name.charAt(0)}
              </div>
              <div className="flex flex-col gap-0.5">
                <p className="font-medium text-sm leading-tight">{user.name}</p>
                <p className="text-xs text-muted-foreground leading-tight">@{user.username} · Telegram</p>
              </div>
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor="timezone">Часовой пояс</Label>
            <Select defaultValue="europe-moscow">
              <SelectTrigger id="timezone" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="europe-moscow">Europe/Moscow</SelectItem>
                <SelectItem value="utc">UTC</SelectItem>
                <SelectItem value="america-new-york">America/New_York</SelectItem>
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
            <Switch id="notifications" defaultChecked />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="reminder-time">Время напоминания</Label>
            <Select defaultValue="1h">
              <SelectTrigger id="reminder-time" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="15m">За 15 минут</SelectItem>
                <SelectItem value="1h">За 1 час</SelectItem>
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
            <Switch id="urgent-reminder" defaultChecked />
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">AI-разбор</h2>
          <div className="flex flex-col gap-2">
            <Label htmlFor="ai-provider">Провайдер</Label>
            <Select defaultValue="groq">
              <SelectTrigger id="ai-provider" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="groq">Groq</SelectItem>
                <SelectItem value="openai">OpenAI</SelectItem>
                <SelectItem value="claude">Claude</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              При недоступности переключится автоматически
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