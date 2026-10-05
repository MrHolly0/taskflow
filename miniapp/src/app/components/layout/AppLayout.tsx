import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  IconSparkles,
  IconMessageCircle,
  IconList,
  IconLayoutKanban,
  IconTags,
  IconChartBar,
  IconSettings,
  IconPlug,
  IconArrowRight,
  IconLogout,
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useStore } from '@/lib/store';
import { useDisplayName } from '@/lib/hooks/useSettings';
import { Button } from '@/app/components/ui/button';
import {
  Sheet,
  SheetContent,
  SheetTrigger,
} from '@/app/components/ui/sheet';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/app/components/ui/dropdown-menu';
import { QuickInputModal } from '@/app/components/QuickInputModal';
import { MuninLogo, MuninBird } from '@/app/components/MuninLogo';
import { useTaskUi } from '@/lib/task-ui';
import { SoundWave } from '@/app/components/CaptureCard';
import { NotificationChannelPrompt } from '@/app/components/NotificationChannelPrompt';

interface NavItem {
  href: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}

const navItems: NavItem[] = [
  { href: '/', label: 'Сейчас', icon: IconSparkles },
  { href: '/assistant', label: 'Ассистент', icon: IconMessageCircle },
  { href: '/all', label: 'Все задачи', icon: IconList },
  { href: '/board', label: 'Доска', icon: IconLayoutKanban },
  { href: '/groups', label: 'Группы', icon: IconTags },
  { href: '/stats', label: 'Статистика', icon: IconChartBar },
  { href: '/integrations', label: 'Интеграции', icon: IconPlug },
  { href: '/settings', label: 'Настройки', icon: IconSettings },
];

export function AppLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const quickInputOpen = useTaskUi(s => s.captureOpen);
  const openCapture = useTaskUi(s => s.openCapture);
  const closeCapture = useTaskUi(s => s.closeCapture);
  const [moreOpen, setMoreOpen] = useState(false);
  const user = useStore((s) => s.user);
  const logout = useStore((s) => s.logout);
  const displayName = useDisplayName();

  return (
    <div className="munin-shell flex h-dvh bg-background pl-[var(--safe-left)] pr-[var(--safe-right)]">
      {/* Desktop Sidebar */}
      <aside className="munin-sidebar hidden lg:flex lg:w-56 lg:flex-col border-r border-border">
        <div className="flex h-14 items-center border-b border-border px-5 flex-shrink-0">
          <MuninLogo variant="header" className="h-5 w-auto text-foreground" />
        </div>

        {/* Quick action */}
        <div className="px-3 pt-3 flex-shrink-0">
          <Button
            variant="outline" className="sidebar-capture w-full justify-center gap-2 h-11"
            onClick={openCapture}
          >
            <MuninBird className="h-5 w-5 flex-shrink-0" />
            <span>Рассказать Мунину</span>
          </Button>
        </div>

        {/* Nav */}
        <nav className="munin-side-nav flex-1 overflow-y-auto py-3 px-3 space-y-0.5">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = location.pathname === item.href;

            return (
              <Link
                key={item.href}
                to={item.href}
                className={cn(
                  'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors',
                  isActive
                    ? 'bg-accent text-accent-foreground font-medium'
                    : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground'
                )}
              >
                <Icon className="h-4 w-4 flex-shrink-0" />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>

      </aside>

      {/* Main Content */}
      <div className="flex flex-1 flex-col min-w-0">
        {/* Top Bar */}
        <header className="flex min-h-[calc(3.5rem+var(--safe-top))] items-center justify-between border-b border-border px-4 lg:px-5 pt-[var(--safe-top)] flex-shrink-0">
          <div className="lg:hidden">
            <MuninLogo variant="header" className="h-5 w-auto text-foreground" />
          </div>

          <div className="ml-auto flex items-center gap-1">
            {user && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button aria-label="Меню аккаунта" className="h-9 w-9 rounded-full bg-primary/15 hover:bg-primary/25 transition-colors flex items-center justify-center text-sm font-semibold text-primary flex-shrink-0 cursor-pointer">
                    {displayName.charAt(0)}
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-56">
                  <DropdownMenuLabel className="space-y-0.5">
                    <p className="text-sm font-medium leading-tight">{displayName}</p>
                    <p className="text-xs text-muted-foreground font-normal leading-tight">@{user.username}</p>
                  </DropdownMenuLabel>
                  <DropdownMenuSeparator />
                  <Link to="/settings">
                    <DropdownMenuItem className="gap-2 cursor-pointer">
                      <IconSettings className="h-4 w-4" />
                      Настройки
                    </DropdownMenuItem>
                  </Link>
                  <DropdownMenuItem onClick={logout} className="gap-2 cursor-pointer text-destructive focus:text-destructive">
                    <IconLogout className="h-4 w-4" />
                    Выйти
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </div>
        </header>

        {/* Page Content */}
        <main className={cn("munin-main flex-1 overflow-y-auto", location.pathname === "/assistant" && "munin-main-chat")}>
          <NotificationChannelPrompt />
          {children}
        </main>

        {location.pathname !== '/assistant' && (
          <div className={cn('mobile-capture-dock', location.pathname === '/' && 'mobile-capture-focus')}>
            <button onClick={openCapture} aria-label="Рассказать Мунину" className="mobile-capture-button">
              {location.pathname === '/' ? <><SoundWave /><span>Рассказать Мунину</span><IconArrowRight /></> : <MuninBird />}
            </button>
          </div>
        )}

        {/* Mobile Bottom Navigation */}
        <nav className="lg:hidden fixed bottom-0 left-0 right-0 z-30 flex items-center border-t border-border bg-background/95 backdrop-blur pb-[var(--safe-bottom)] pl-[var(--safe-left)] pr-[var(--safe-right)]">
          {[
            { href: '/', icon: IconSparkles, label: 'Сейчас' },
            { href: '/assistant', icon: IconMessageCircle, label: 'Ассистент' },
            { href: '/all', icon: IconList, label: 'Задачи' },
            { href: '/board', icon: IconLayoutKanban, label: 'Доска' },
            { href: '/groups', icon: IconTags, label: 'Группы' },
          ].map(({ href, icon: Icon, label }) => {
            const isActive = location.pathname === href;
            return (
              <Link
                key={href}
                to={href}
                className={cn(
                  'flex-1 flex flex-col items-center gap-1 py-3 text-xs transition-colors',
                  isActive ? 'text-primary' : 'text-muted-foreground'
                )}
              >
                <Icon className="h-5 w-5" />
                <span>{label}</span>
              </Link>
            );
          })}

          {/* More */}
          <Sheet open={moreOpen} onOpenChange={setMoreOpen}>
            <SheetTrigger asChild>
              <button className="flex-1 flex flex-col items-center gap-1 py-3 text-xs text-muted-foreground cursor-pointer">
                <IconSettings className="h-5 w-5" />
                <span>Еще</span>
              </button>
            </SheetTrigger>
            <SheetContent side="bottom" className="h-auto rounded-t-2xl">
              <div className="space-y-1 py-2">
                {[
                  { href: '/stats', icon: IconChartBar, label: 'Статистика' },
                  { href: '/integrations', icon: IconPlug, label: 'Интеграции' },
                  { href: '/settings', icon: IconSettings, label: 'Настройки' },
                ].map(({ href, icon: Icon, label }) => (
                  <Link
                    key={href}
                    to={href}
                    onClick={() => setMoreOpen(false)}
                    className="flex items-center gap-3 rounded-xl px-4 py-3 hover:bg-accent transition-colors"
                  >
                    <Icon className="h-5 w-5 text-muted-foreground" />
                    <span className="font-medium">{label}</span>
                  </Link>
                ))}
                {user && (
                  <button
                    onClick={logout}
                    className="w-full flex items-center gap-3 rounded-xl px-4 py-3 hover:bg-accent transition-colors text-muted-foreground cursor-pointer"
                  >
                    <IconLogout className="h-5 w-5" />
                    <span>Выйти</span>
                  </button>
                )}
              </div>
            </SheetContent>
          </Sheet>
        </nav>
      </div>

      {/* Quick Input Modal */}
      <QuickInputModal open={quickInputOpen} onClose={closeCapture} />
    </div>
  );
}
