import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  IconSparkles,
  IconList,
  IconLayoutKanban,
  IconTags,
  IconChartBar,
  IconSettings,
  IconPlus,
  IconMoon,
  IconSun,
  IconLogout,
} from '@tabler/icons-react';
import { useTheme } from 'next-themes';
import { cn } from '@/lib/utils';
import { useStore } from '@/lib/store';
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

interface NavItem {
  href: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}

const navItems: NavItem[] = [
  { href: '/', label: 'Сейчас', icon: IconSparkles },
  { href: '/all', label: 'Все задачи', icon: IconList },
  { href: '/board', label: 'Доска', icon: IconLayoutKanban },
  { href: '/groups', label: 'Группы', icon: IconTags },
  { href: '/stats', label: 'Статистика', icon: IconChartBar },
  { href: '/settings', label: 'Настройки', icon: IconSettings },
];

export function AppLayout({ children }: { children: React.ReactNode }) {
  const location = useLocation();
  const { theme, setTheme } = useTheme();
  const [quickInputOpen, setQuickInputOpen] = useState(false);
  const user = useStore((s) => s.user);
  const logout = useStore((s) => s.logout);

  return (
    <div className="flex h-screen bg-background">
      {/* Desktop Sidebar */}
      <aside className="hidden lg:flex lg:w-56 lg:flex-col border-r border-border">
        {/* Logo */}
        <div className="flex h-14 items-center border-b border-border px-5 flex-shrink-0">
          <div className="flex items-center gap-2">
            <IconSparkles className="h-5 w-5 text-primary" />
            <h1 className="text-base font-semibold">TaskFlow</h1>
          </div>
        </div>

        {/* Quick action */}
        <div className="px-3 pt-3 flex-shrink-0">
          <Button
            className="w-full justify-start gap-2.5 h-10"
            onClick={() => setQuickInputOpen(true)}
          >
            <IconPlus className="h-4 w-4 flex-shrink-0" />
            <span>Новая задача</span>
          </Button>
        </div>

        {/* Nav */}
        <nav className="flex-1 overflow-y-auto py-3 px-3 space-y-0.5">
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
        <header className="flex h-14 items-center justify-between border-b border-border px-4 lg:px-5 flex-shrink-0">
          <div className="lg:hidden">
            <div className="flex items-center gap-2">
              <IconSparkles className="h-4 w-4 text-primary" />
              <h1 className="text-base font-semibold">TaskFlow</h1>
            </div>
          </div>

          <div className="ml-auto flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              className="h-9 w-9"
              onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
              title="Сменить тему"
            >
              {theme === 'dark' ? (
                <IconSun className="h-4 w-4" />
              ) : (
                <IconMoon className="h-4 w-4" />
              )}
            </Button>
            {user && (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button className="h-9 w-9 rounded-full bg-primary/15 hover:bg-primary/25 transition-colors flex items-center justify-center text-sm font-semibold text-primary flex-shrink-0 cursor-pointer">
                    {user.name.charAt(0)}
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-56">
                  <DropdownMenuLabel className="space-y-0.5">
                    <p className="text-sm font-medium leading-tight">{user.name}</p>
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
        <main className="flex-1 overflow-y-auto p-4 lg:p-6 pb-24 lg:pb-8">
          {children}
        </main>

        {/* Mobile FAB */}
        <button
          onClick={() => setQuickInputOpen(true)}
          className="lg:hidden fixed bottom-20 right-4 z-40 h-14 w-14 rounded-full bg-primary text-primary-foreground shadow-lg flex items-center justify-center active:scale-95 transition-transform cursor-pointer"
        >
          <IconPlus className="h-6 w-6" />
        </button>

        {/* Mobile Bottom Navigation */}
        <nav className="lg:hidden fixed bottom-0 left-0 right-0 z-30 flex items-center border-t border-border bg-background/95 backdrop-blur">
          {[
            { href: '/', icon: IconSparkles, label: 'Сейчас' },
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
          <Sheet>
            <SheetTrigger asChild>
              <button className="flex-1 flex flex-col items-center gap-1 py-3 text-xs text-muted-foreground cursor-pointer">
                <IconSettings className="h-5 w-5" />
                <span>Ещё</span>
              </button>
            </SheetTrigger>
            <SheetContent side="bottom" className="h-auto rounded-t-2xl">
              <div className="space-y-1 py-2">
                {[
                  { href: '/stats', icon: IconChartBar, label: 'Статистика' },
                  { href: '/settings', icon: IconSettings, label: 'Настройки' },
                ].map(({ href, icon: Icon, label }) => (
                  <Link
                    key={href}
                    to={href}
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
      <QuickInputModal open={quickInputOpen} onClose={() => setQuickInputOpen(false)} />
    </div>
  );
}
