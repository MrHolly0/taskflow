import { useState, useEffect, useRef } from 'react';
import { IconBrandTelegram, IconSparkles, IconBolt, IconShield } from '@tabler/icons-react';
import { motion } from 'motion/react';
import { useStore } from '@/lib/store';
import { authenticateViaInitData, authenticateAsDemoUser, authenticateViaLoginWidget, isTelegramWebApp, getStoredToken, getUserFromToken } from '@/lib/auth';

const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME || 'MHTaskFlowAI_Bot';

function TelegramLoginWidget({ onAuth }: { onAuth: (user: Record<string, string | number>) => void }) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    (window as any).onTelegramWidgetAuth = onAuth;
    const script = document.createElement('script');
    script.src = 'https://telegram.org/js/telegram-widget.js?22';
    script.setAttribute('data-telegram-login', BOT_USERNAME);
    script.setAttribute('data-size', 'large');
    script.setAttribute('data-onauth', 'onTelegramWidgetAuth(user)');
    script.setAttribute('data-request-access', 'write');
    script.async = true;
    containerRef.current.innerHTML = '';
    containerRef.current.appendChild(script);
    return () => { delete (window as any).onTelegramWidgetAuth; };
  }, [onAuth]);

  return <div ref={containerRef} className="flex justify-center" />;
}

const features = [
  {
    icon: IconSparkles,
    title: 'Фокус-режим',
    desc: '1–3 задачи. Только самое важное.',
  },
  {
    icon: IconBolt,
    title: 'Голосовой ввод',
    desc: 'Надиктуй задачу — AI разберёт сам.',
  },
  {
    icon: IconShield,
    title: 'Без перегруза',
    desc: 'Ассистент решает приоритеты за тебя.',
  },
];

export function AuthPage() {
  const setAuthenticated = useStore((s) => s.setAuthenticated);
  const login = useStore((s) => s.login);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const applyAuth = () => {
    const token = getStoredToken();
    if (token) {
      const info = getUserFromToken(token);
      if (info) login({ id: info.id, name: info.username, username: info.username });
    }
    setAuthenticated(true);
  };

  const handleTelegramLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      await authenticateViaInitData();
      applyAuth();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Ошибка авторизации';
      setError(message);
      console.error('Auth error:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleDemoLogin = async () => {
    setLoading(true);
    setError(null);
    try {
      await authenticateAsDemoUser();
      applyAuth();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Ошибка авторизации';
      setError(message);
      console.error('Demo auth error:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleWidgetAuth = async (user: Record<string, string | number>) => {
    setLoading(true);
    setError(null);
    try {
      await authenticateViaLoginWidget(user);
      applyAuth();
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Ошибка авторизации через Telegram';
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="space-y-10"
        >
          {/* Logo */}
          <div className="text-center space-y-3">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-primary/10 text-primary">
              <IconSparkles className="w-8 h-8" />
            </div>
            <div>
              <h1 className="text-4xl font-bold tracking-tight">TaskFlow</h1>
              <p className="text-muted-foreground mt-2 text-base">
                Планировщик для мозга, который не любит скучать
              </p>
            </div>
          </div>

          {/* Features */}
          <div className="space-y-3">
            {features.map((f, i) => {
              const Icon = f.icon;
              return (
                <motion.div
                  key={f.title}
                  initial={{ opacity: 0, x: -16 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.2 + i * 0.1, duration: 0.4 }}
                  className="flex items-center gap-4 p-4 rounded-xl bg-muted/50 border border-border/50"
                >
                  <div className="flex-shrink-0 w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center">
                    <Icon className="w-5 h-5 text-primary" />
                  </div>
                  <div>
                    <div className="font-medium text-sm">{f.title}</div>
                    <div className="text-muted-foreground text-sm">{f.desc}</div>
                  </div>
                </motion.div>
              );
            })}
          </div>

          {/* CTA */}
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.6, duration: 0.4 }}
            className="space-y-4"
          >
            {isTelegramWebApp() ? (
              <button
                onClick={handleTelegramLogin}
                disabled={loading}
                className="w-full flex items-center justify-center gap-3 py-4 px-6 rounded-2xl font-semibold text-white transition-all active:scale-95 cursor-pointer disabled:opacity-70 disabled:cursor-not-allowed"
                style={{ backgroundColor: '#0088cc' }}
              >
                {loading ? (
                  <>
                    <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Входим...
                  </>
                ) : (
                  <>
                    <IconBrandTelegram className="w-6 h-6" />
                    Войти через Telegram
                  </>
                )}
              </button>
            ) : (
              <>
                <button
                  onClick={handleDemoLogin}
                  disabled={loading}
                  className="w-full flex items-center justify-center gap-3 py-4 px-6 rounded-2xl font-semibold transition-all active:scale-95 cursor-pointer disabled:opacity-70 disabled:cursor-not-allowed border border-border bg-muted/50 text-foreground"
                >
                  {loading ? (
                    <>
                      <div className="w-5 h-5 border-2 border-foreground/30 border-t-foreground rounded-full animate-spin" />
                      Входим...
                    </>
                  ) : (
                    'Попробовать демо'
                  )}
                </button>
                <TelegramLoginWidget onAuth={handleWidgetAuth} />
              </>
            )}

            {error && (
              <p className="text-center text-xs text-red-500 px-4">
                {error}
              </p>
            )}

            <p className="text-center text-xs text-muted-foreground px-4">
              Авторизация через Telegram — безопасно и без паролей.
              Мы не храним личные данные.
            </p>
          </motion.div>
        </motion.div>
      </div>
    </div>
  );
}
