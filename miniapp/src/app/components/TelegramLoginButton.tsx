import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import { TelegramGlyph } from '@/app/components/TelegramLogo';

const BOT_ID = import.meta.env.VITE_TELEGRAM_BOT_ID || '8738130486';
const HASH_PREFIX = '#tgAuthResult=';

type Props = {
  onAuth: (fields: Record<string, string>) => void;
  label?: string;
  className?: string;
};

type AuthResult =
  | { status: 'absent' }
  | { status: 'ok'; fields: Record<string, string> }
  | { status: 'error' };

// Хеш есть, но не разобрался, — не то же самое, что хеша нет: первое надо
// показать человеку, второе — обычный заход, о котором и говорить нечего.
// base64url (- и _ вместо + и /, без паддинга) принимаем на случай, если
// Telegram когда-нибудь его использует, — не только классический base64.
function readAuthResult(): AuthResult {
  if (!window.location.hash.startsWith(HASH_PREFIX)) return { status: 'absent' };
  try {
    const base64 = decodeURIComponent(window.location.hash.slice(HASH_PREFIX.length))
      .replace(/-/g, '+')
      .replace(/_/g, '/');
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
    const data = JSON.parse(atob(padded)) as Record<string, unknown>;
    const fields: Record<string, string> = {};
    for (const [key, value] of Object.entries(data)) fields[key] = String(value);
    return { status: 'ok', fields };
  } catch {
    return { status: 'error' };
  }
}

// Виджет telegram-widget.js рисует чужой iframe и требует внешний скрипт,
// который в России не грузится. oauth.telegram.org/auth — тот же путь входа
// без скрипта: обычная страница, куда переходим сами, а Telegram возвращает
// на return_to с результатом в хеше (#tgAuthResult=<base64>). Полный переход
// страницы, а не всплывающее окно, — если человек отменит вход у Telegram,
// он просто не окажется здесь с этим хешем, и страница не зависает в
// промежуточном состоянии, потому что мы его и не заводили.
export function TelegramLoginButton({ onAuth, label = 'Telegram', className }: Props) {
  const [parseError, setParseError] = useState(false);

  useEffect(() => {
    const result = readAuthResult();
    if (result.status === 'absent') return;
    history.replaceState(null, '', window.location.pathname + window.location.search);
    if (result.status === 'error') {
      setParseError(true);
      return;
    }
    onAuth(result.fields);
  }, [onAuth]);

  const handleClick = () => {
    setParseError(false);
    const params = new URLSearchParams({
      bot_id: BOT_ID,
      origin: window.location.origin,
      request_access: 'write',
      return_to: window.location.href,
    });
    window.location.href = `https://oauth.telegram.org/auth?${params.toString()}`;
  };

  return (
    <div className="flex flex-col items-center gap-2">
      <button
        type="button"
        onClick={handleClick}
        className={cn('flex items-center justify-center gap-3 py-3 px-6 rounded-2xl font-semibold text-white transition-all active:scale-95 cursor-pointer bg-[#2AABEE] hover:bg-[#229ED9]', className)}
      >
        {label === 'Telegram' && <TelegramGlyph className="h-5 w-5 text-white" />}
        {label}
      </button>
      {parseError && (
        <p className="text-xs text-destructive text-center">
          Не получилось разобрать ответ от Telegram. Попробуйте войти еще раз.
        </p>
      )}
    </div>
  );
}
