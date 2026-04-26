import { useState } from 'react';
import { IconBrandTelegram, IconSparkles, IconBolt, IconShield } from '@tabler/icons-react';
import { motion } from 'motion/react';
import { useStore, User } from '@/lib/store';

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
  const login = useStore((s) => s.login);
  const [loading, setLoading] = useState(false);

  const handleTelegramLogin = () => {
    setLoading(true);
    setTimeout(() => {
      const user: User = {
        id: 'tg_123456',
        name: 'Саша',
        username: 'sasha_dev',
        avatar: undefined,
      };
      login(user);
    }, 1800);
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
