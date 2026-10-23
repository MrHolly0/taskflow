import { useEffect, useState, lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { ThemeProvider } from 'next-themes';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { shouldRetryQuery, queryRetryDelay } from '@/lib/request-policy';
import { Toaster } from '@/app/components/ui/sonner';
import { AppLayout } from '@/app/components/layout/AppLayout';
import { AuthPage } from '@/app/pages/AuthPage';
import { useStore } from '@/lib/store';

// По маршруту, не всё разом: раньше все восемь страниц уходили в один чанк
// 1.2 МБ вместе со стартовым экраном — в мобильной сети мини-приложения это
// заметно. AuthPage — не Route, а условный рендер до входа, нужен сразу же
// почти всем, кто открывает приложение впервые, поэтому остаётся статическим.
const FocusPage = lazy(() => import('@/app/pages/FocusPage').then((m) => ({ default: m.FocusPage })));
const AssistantPage = lazy(() => import('@/app/pages/AssistantPage').then((m) => ({ default: m.AssistantPage })));
const AllTasksPage = lazy(() => import('@/app/pages/AllTasksPage').then((m) => ({ default: m.AllTasksPage })));
const BoardPage = lazy(() => import('@/app/pages/BoardPage').then((m) => ({ default: m.BoardPage })));
const GroupsPage = lazy(() => import('@/app/pages/GroupsPage').then((m) => ({ default: m.GroupsPage })));
const StatsPage = lazy(() => import('@/app/pages/StatsPage').then((m) => ({ default: m.StatsPage })));
const SettingsPage = lazy(() => import('@/app/pages/SettingsPage').then((m) => ({ default: m.SettingsPage })));
const IntegrationsPage = lazy(() => import('@/app/pages/IntegrationsPage').then((m) => ({ default: m.IntegrationsPage })));
import { getStoredToken, authenticateViaInitData, isTelegramWebApp, initializeTelegramWebApp, getUserFromToken } from '@/lib/auth';
import { setApiToken } from '@/lib/api';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30000,
      retry: shouldRetryQuery,
      retryDelay: queryRetryDelay,
      refetchOnWindowFocus: false,
    },
  },
});

function AppContent() {
  const isAuthenticated = useStore((s) => s.isAuthenticated);
  const setAuthenticated = useStore((s) => s.setAuthenticated);
  const login = useStore((s) => s.login);
  const [isInitializing, setIsInitializing] = useState(true);

  const applyToken = (token: string): boolean => {
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      if (payload.exp && payload.exp * 1000 < Date.now()) {
        localStorage.removeItem('auth_token');
        return false;
      }
    } catch { /* malformed – treat as valid and let API reject it */ }

    const info = getUserFromToken(token);
    if (info) {
      setApiToken(token);
      login({ id: info.id, name: info.username, username: info.username });
    }
    setAuthenticated(true);
    setIsInitializing(false);
    return true;
  };

  useEffect(() => {
    initializeTelegramWebApp();

    if (isTelegramWebApp()) {
      authenticateViaInitData()
        .then(() => {
          const t = getStoredToken();
          if (t) applyToken(t);
          else { setAuthenticated(true); setIsInitializing(false); }
        })
        .catch(() => {
          const token = getStoredToken();
          if (token && applyToken(token)) return;
          setIsInitializing(false);
        });
    } else {
      const token = getStoredToken();
      if (token && applyToken(token)) return;
      setIsInitializing(false);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (isInitializing) {
    return (
      <div className="flex items-center justify-center min-h-dvh">
        <p className="text-muted-foreground">Инициализация...</p>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <AuthPage />;
  }

  return (
    <AppLayout>
      <Suspense fallback={<div className="min-h-[50vh]" />}>
        <Routes>
          <Route path="/" element={<FocusPage />} />
          <Route path="/assistant" element={<AssistantPage />} />
          <Route path="/all" element={<AllTasksPage />} />
          <Route path="/board" element={<BoardPage />} />
          <Route path="/groups" element={<GroupsPage />} />
          <Route path="/stats" element={<StatsPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/integrations" element={<IntegrationsPage />} />
        </Routes>
      </Suspense>
    </AppLayout>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
        <BrowserRouter>
          <AppContent />
          <Toaster />
        </BrowserRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
