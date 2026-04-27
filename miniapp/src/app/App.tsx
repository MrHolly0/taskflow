import { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { ThemeProvider } from 'next-themes';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from '@/app/components/ui/sonner';
import { AppLayout } from '@/app/components/layout/AppLayout';
import { FocusPage } from '@/app/pages/FocusPage';
import { AllTasksPage } from '@/app/pages/AllTasksPage';
import { BoardPage } from '@/app/pages/BoardPage';
import { GroupsPage } from '@/app/pages/GroupsPage';
import { StatsPage } from '@/app/pages/StatsPage';
import { SettingsPage } from '@/app/pages/SettingsPage';
import { AuthPage } from '@/app/pages/AuthPage';
import { useStore } from '@/lib/store';
import { getStoredToken, authenticateViaInitData, isTelegramWebApp, initializeTelegramWebApp, getUserFromToken } from '@/lib/auth';
import { setApiToken } from '@/lib/api';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30000,
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

  const reAuthOrShowLogin = () => {
    if (isTelegramWebApp()) {
      authenticateViaInitData()
        .then(() => {
          const t = getStoredToken();
          if (t) applyToken(t); else { setAuthenticated(true); setIsInitializing(false); }
        })
        .catch(() => setIsInitializing(false));
    } else {
      setIsInitializing(false);
    }
  };

  useEffect(() => {
    initializeTelegramWebApp();
    const token = getStoredToken();
    if (token && applyToken(token)) return;
    reAuthOrShowLogin();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (isInitializing) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <p className="text-muted-foreground">Инициализация...</p>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <AuthPage />;
  }

  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<FocusPage />} />
        <Route path="/all" element={<AllTasksPage />} />
        <Route path="/board" element={<BoardPage />} />
        <Route path="/groups" element={<GroupsPage />} />
        <Route path="/stats" element={<StatsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
      </Routes>
    </AppLayout>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem={true}>
        <BrowserRouter>
          <AppContent />
          <Toaster />
        </BrowserRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
