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
import { getStoredToken, authenticateViaInitData, initializeTelegramWebApp } from '@/lib/auth';

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
  const [isInitializing, setIsInitializing] = useState(true);

  useEffect(() => {
    initializeTelegramWebApp();
    const token = getStoredToken();

    if (token) {
      setAuthenticated(true);
      setIsInitializing(false);
    } else {
      authenticateViaInitData()
        .then(() => {
          setAuthenticated(true);
          setIsInitializing(false);
        })
        .catch((error) => {
          console.error('Authentication failed:', error);
          setIsInitializing(false);
        });
    }
  }, [setAuthenticated]);

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
      <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
        <BrowserRouter>
          <AppContent />
          <Toaster />
        </BrowserRouter>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
