import { useEffect, useRef, useState } from 'react';
import { IconArrowLeft, IconPhone, IconCopy, IconCheck } from '@tabler/icons-react';
import { Button, buttonVariants } from '@/app/components/ui/button';
import { cn } from '@/lib/utils';
import { formatPhoneInput } from '@/lib/phoneMask';
import { sequentialPoll } from '@/lib/sequential-poll';
import { queryRetryDelay } from '@/lib/request-policy';

const TOTAL_WAIT_SECONDS = 5 * 60;
const POLL_INTERVAL_MS = 2000;

export interface PhoneConfirmationStatus {
  status: 'waiting' | 'confirmed' | 'expired' | 'conflict';
}

type Props<T extends PhoneConfirmationStatus> = {
  phone: string;
  confirmationNumber: string;
  onBack: () => void;
  onConfirmed: (result: T) => void;
  pollStatus: (phone: string) => Promise<T>;
  cancel: (phone: string) => Promise<void>;
};

// Основной способ подтверждения теперь — звонок человека нам, не нам ему:
// исходящий звонок на реальном номере не доходил, хотя Ucaller каждый раз
// отчитывался об успехе (call_status: 1). Входящий звонок эту проблему
// снимает устройством, а не починкой — заблокировать исходящий вызов
// человека некому.
export function PhoneWaitingStep<T extends PhoneConfirmationStatus>({
  phone,
  confirmationNumber,
  onBack,
  onConfirmed,
  pollStatus,
  cancel,
}: Props<T>) {
  const [secondsLeft, setSecondsLeft] = useState(TOTAL_WAIT_SECONDS);
  const [expired, setExpired] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [copied, setCopied] = useState(false);
  const stoppedRef = useRef(false);
  const stopPoll = useRef<(() => void) | undefined>();
  const dialableNumber = `+${confirmationNumber.replace(/\D/g, '')}`;

  useEffect(() => {
    stoppedRef.current = false;
    setSecondsLeft(TOTAL_WAIT_SECONDS);
    setExpired(false);
    const stop = sequentialPoll(() => pollStatus(phone), result => {
      if (stoppedRef.current) return false;
      if (result.status === 'waiting') return true;
      stoppedRef.current = true;
      if (result.status === 'expired') setExpired(true);
      else onConfirmed(result);
      return false;
    }, (failures, error) => failures
      ? Math.max(POLL_INTERVAL_MS, queryRetryDelay(failures, error))
      : POLL_INTERVAL_MS + Math.random() * 250);
    stopPoll.current = stop;
    return () => { stoppedRef.current = true; stop(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phone]);

  useEffect(() => {
    if (expired || stoppedRef.current) return;
    if (secondsLeft <= 0) {
      stoppedRef.current = true;
      stopPoll.current?.();
      setExpired(true);
      return;
    }
    const countdown = setInterval(() => setSecondsLeft((s) => s - 1), 1000);
    return () => clearInterval(countdown);
  }, [secondsLeft, expired]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(dialableNumber);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Буфер обмена недоступен — номер всё равно виден и его можно выделить руками.
    }
  };

  const handleCancel = async () => {
    if (cancelling) return;
    setCancelling(true);
    stoppedRef.current = true;
    stopPoll.current?.();
    try {
      await cancel(phone);
    } catch {
      // Отмена — best effort: если запрос не дошёл, запись всё равно
      // протухнет сама по TTL через пять минут.
    } finally {
      onBack();
    }
  };

  if (expired) {
    return (
      <div className="space-y-6">
        <div className="space-y-1.5 text-center">
          <h2 className="text-lg font-semibold">Время вышло</h2>
          <p className="text-sm text-muted-foreground">
            Подтверждение не пришло за пять минут — возможно, звонок не
            дошёл. Попробуйте ещё раз.
          </p>
        </div>
        <Button onClick={onBack} className="w-full h-11">Начать заново</Button>
      </div>
    );
  }

  const minutes = Math.floor(secondsLeft / 60);
  const seconds = secondsLeft % 60;
  const displayNumber = formatPhoneInput(confirmationNumber);

  return (
    <div className="space-y-6">
      <button
        type="button"
        onClick={handleCancel}
        disabled={cancelling}
        className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
      >
        <IconArrowLeft className="w-4 h-4" />
        {cancelling ? 'Отменяем...' : 'Назад'}
      </button>

      <div className="space-y-1.5 text-center">
        <h2 className="text-lg font-semibold">Позвоните на этот номер</h2>
        <p className="text-sm text-muted-foreground">
          Отвечать не нужно — звонок можно сбросить сразу после соединения.
        </p>
      </div>

      <div className="space-y-3">
        <p className="text-center text-3xl font-mono font-semibold tracking-wide select-all">
          {displayNumber}
        </p>

        <div className="flex items-center gap-2">
          <a
            href={`tel:${dialableNumber}`}
            className={cn(buttonVariants({ size: 'lg' }), 'flex-1')}
          >
            <IconPhone className="w-4 h-4" />
            Позвонить
          </a>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={handleCopy}
            className="shrink-0"
          >
            {copied ? <IconCheck className="w-4 h-4" /> : <IconCopy className="w-4 h-4" />}
            {copied ? 'Скопировано' : 'Скопировать'}
          </Button>
        </div>
      </div>

      <p className="text-center text-xs text-muted-foreground">
        Ждём звонка — осталось {minutes}:{seconds.toString().padStart(2, '0')}
      </p>
    </div>
  );
}
