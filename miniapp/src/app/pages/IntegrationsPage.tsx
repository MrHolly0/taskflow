import { useState, useCallback } from 'react';
import { toast } from 'sonner';
import { IconMail, IconPhone, IconShieldCheck } from '@tabler/icons-react';
import { Card } from '@/app/components/ui/card';
import { Button, buttonVariants } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Separator } from '@/app/components/ui/separator';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/app/components/ui/alert-dialog';
import { cn } from '@/lib/utils';
import '@/styles/integrations.css';
import { EmailCodeStep } from '@/app/components/EmailCodeStep';
import { PhoneWaitingStep } from '@/app/components/PhoneWaitingStep';
import { TelegramLoginButton } from '@/app/components/TelegramLoginButton';
import { TelegramGlyph } from '@/app/components/TelegramLogo';
import { MergeConflictDialog } from '@/app/components/MergeConflictDialog';
import { isTelegramWebApp } from '@/lib/auth';
import { plural } from '@/lib/plural';
import { useAuthMethods } from '@/lib/hooks/useAuthMethods';
import { formatPhoneInput } from '@/lib/phoneMask';
import {
  useIdentities,
  useRequestBindEmailCode,
  useConfirmBindEmail,
  useRequestBindPhoneConfirmation,
  usePollBindPhoneConfirmation,
  useCancelBindPhoneConfirmation,
  useBindTelegram,
  useMergeAccounts,
  useUnbindIdentity,
  isMergeConflict,
  type AccountTransferResult,
  type IdentityBindResponse,
  type IdentityProvider,
  type MergeConflictResponse,
  type PhoneBindConfirmationStatus,
} from '@/lib/hooks/useIdentities';

function identityErrorDetail(error: unknown): string | undefined {
  if (typeof error !== 'object' || error === null || !('response' in error)) return undefined;
  const response = error.response;
  if (typeof response !== 'object' || response === null || !('data' in response)) return undefined;
  const data = response.data;
  if (typeof data !== 'object' || data === null || !('detail' in data)) return undefined;
  return typeof data.detail === 'string' ? data.detail : undefined;
}

const EMPTY_TRANSFER: AccountTransferResult = {
  tasks: 0, groups: 0, tags: 0, notifications: 0, auditEvents: 0, proposals: 0, identities: 0,
};

function mergeToastMessage(result: AccountTransferResult): string {
  const parts: string[] = [];
  if (result.tasks > 0) parts.push(`${result.tasks} ${plural(result.tasks, ['задача', 'задачи', 'задач'])}`);
  if (result.groups > 0) parts.push(`${result.groups} ${plural(result.groups, ['группа', 'группы', 'групп'])}`);
  if (result.tags > 0) parts.push(`${result.tags} ${plural(result.tags, ['метка', 'метки', 'меток'])}`);
  if (parts.length === 0) return 'Способ входа подключен — данные с ним переносить не пришлось.';
  return `Перенесли с прежней учетки: ${parts.join(', ')}.`;
}

// Доказательство владения идентификатором и согласие на слияние двух
// учёток — разные вещи. При 409 от confirm/telegram показываем диалог
// с числами и ждём явного «Перенести»; «Отмена» не должна молча оставить
// способ входа непривязанным — сообщаем об этом прямо и даём начать заново.
function useMergeFlow(onMerged: (result: IdentityBindResponse) => void, onResolved: () => void) {
  const [conflict, setConflict] = useState<MergeConflictResponse | null>(null);
  const mergeAccounts = useMergeAccounts();

  const catchConflict = useCallback((err: unknown): boolean => {
    if (isMergeConflict(err)) {
      setConflict(err.response.data);
      return true;
    }
    return false;
  }, []);

  const confirm = () => {
    if (!conflict) return;
    mergeAccounts.mutate(conflict.mergeToken, {
      onSuccess: (result) => {
        setConflict(null);
        onMerged(result);
        onResolved();
      },
      onError: () => {
        toast.error('Не получилось перенести данные, попробуйте еще раз');
      },
    });
  };

  const cancel = () => {
    setConflict(null);
    toast.info('Способ входа не подключен. Можно начать заново.');
    onResolved();
  };

  return { conflict, merging: mergeAccounts.isPending, catchConflict, presentConflict: setConflict, confirm, cancel };
}

function IntegrationsHeader() {
  return (
    <header className="munin-integrations-heading">
      <h1>Интеграции</h1>
      <p>Один аккаунт, несколько способов входа.</p>
    </header>
  );
}

function IdentityState({ connected, children }: { connected: boolean; children: React.ReactNode }) {
  return (
    <span className={cn('munin-identity-state', connected && 'is-connected')}>
      {children}
    </span>
  );
}

function SectionSkeleton() {
  return (
    <div className="munin-identity-provider" aria-hidden="true">
      <div className="h-10 w-10 shrink-0 rounded-lg bg-muted animate-pulse" />
      <div className="flex flex-col gap-1.5">
        <div className="h-3.5 w-20 rounded bg-muted animate-pulse" />
        <div className="h-3 w-28 rounded bg-muted animate-pulse" />
      </div>
    </div>
  );
}

export function IntegrationsPage() {
  const { data: identities, isLoading, isError, refetch } = useIdentities();
  const { data: authMethods } = useAuthMethods();
  const unbind = useUnbindIdentity();

  // Элементы управления, меняющие состояние учётки, не рисуются, пока
  // состояние неизвестно: пустой identities по умолчанию неотличим от
  // «загрузка ещё идёт» или «запрос упал», а разделы это разные вещи —
  // «ничего не подключено» и «не знаем, что подключено».
  if (isLoading) {
    return (
      <div className="munin-integrations">
        <IntegrationsHeader />
        <Card className="p-6 flex flex-col gap-6">
          <SectionSkeleton />
          <Separator />
          <SectionSkeleton />
        </Card>
      </div>
    );
  }

  if (isError || !identities) {
    return (
      <div className="munin-integrations">
        <IntegrationsHeader />
        <Card className="p-6">
          <div className="flex flex-col items-center gap-3 py-6 text-center">
            <p className="text-sm text-muted-foreground">Не удалось загрузить способы входа.</p>
            <Button variant="outline" size="sm" onClick={() => refetch()}>Повторить</Button>
          </div>
        </Card>
      </div>
    );
  }

  const telegram = identities.find((i) => i.provider === 'TELEGRAM');
  const email = identities.find((i) => i.provider === 'EMAIL');
  const phone = identities.find((i) => i.provider === 'PHONE');
  const canUnbind = identities.length > 1;
  // Пока ответ не пришёл, data === undefined — раздел телефона остаётся
  // скрытым, как и на AuthPage: неизвестное состояние не рисуем как
  // разрешающее. Уже привязанный номер показываем всегда — провайдер мог
  // стать недоступен позже, отвязать способ входа это не должно мешать.
  const phoneAvailable = authMethods?.phone === true;
  const showPhoneSection = phoneAvailable || Boolean(phone);

  const handleUnbind = (provider: IdentityProvider) => {
    unbind.mutate(provider, {
      onError: (err) => toast.error(identityErrorDetail(err) ?? 'Не получилось отвязать способ входа'),
    });
  };

  return (
    <div className="munin-integrations">
      <IntegrationsHeader />

      <div className="munin-access-note">
        <IconShieldCheck size={18} aria-hidden="true" />
        <p>{identities.length > 1 ? 'Запасной способ входа подключён.' : 'Подключи второй способ входа, чтобы сохранить доступ к задачам.'}</p>
      </div>
      <div className="munin-identity-list">
        <TelegramSection
          connected={Boolean(telegram)}
          canUnbind={canUnbind}
          onUnbind={() => handleUnbind('TELEGRAM')}
          unbinding={unbind.isPending && unbind.variables === 'TELEGRAM'}
        />

        <Separator />

        <EmailSection
          connected={Boolean(email)}
          externalId={email?.externalId}
          canUnbind={canUnbind}
          onUnbind={() => handleUnbind('EMAIL')}
          unbinding={unbind.isPending && unbind.variables === 'EMAIL'}
        />

        {showPhoneSection && (
          <>
            <Separator />

            <PhoneSection
              connected={Boolean(phone)}
              externalId={phone?.externalId}
              canUnbind={canUnbind}
              onUnbind={() => handleUnbind('PHONE')}
              unbinding={unbind.isPending && unbind.variables === 'PHONE'}
              available={phoneAvailable}
            />
          </>
        )}
      </div>
      <div className="munin-identity-help">
        {identities.length === 1 && <p>Единственный способ входа нельзя отключить.</p>}
        <details>
          <summary>Если у меня уже есть другой аккаунт?</summary>
          <p>При подключении почты или Telegram предложим перенести задачи из прежнего аккаунта. Число задач и групп появится до твоего подтверждения.</p>
        </details>
      </div>
    </div>
  );
}

function TelegramSection({
  connected,
  canUnbind,
  onUnbind,
  unbinding,
}: {
  connected: boolean;
  canUnbind: boolean;
  onUnbind: () => void;
  unbinding: boolean;
}) {
  const bindTelegram = useBindTelegram();
  const merge = useMergeFlow(
    (result) => toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER)),
    () => {},
  );

  const handleAuth = (fields: Record<string, string>) => {
    bindTelegram.mutate(fields, {
      onSuccess: (result) => {
        toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER));
      },
      onError: (err) => {
        if (merge.catchConflict(err)) return;
        toast.error(identityErrorDetail(err) ?? 'Не получилось привязать Telegram');
      },
    });
  };

  return (
    <section className="munin-identity-row">
      <div className="munin-identity-summary">
        <div className="munin-identity-provider">
          <span className="munin-identity-icon" aria-hidden="true">
            <TelegramGlyph className="h-5 w-5" />
          </span>
          <div>
            <p className="text-sm font-medium">Telegram</p>
            <IdentityState connected={connected}>{connected ? 'Подключён' : 'Вход через Telegram'}</IdentityState>
          </div>
        </div>

        {connected ? (
          <AlertDialog>
            {/* Без asChild: Button — обычная функция без forwardRef. */}
            <AlertDialogTrigger
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
              disabled={!canUnbind || unbinding}
              title={!canUnbind ? 'Это единственный способ входа' : undefined}
            >
              {unbinding ? 'Отвязываем...' : 'Отвязать'}
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Отвязать Telegram?</AlertDialogTitle>
                <AlertDialogDescription>
                  Больше нельзя будет войти через Telegram. Задачи и данные останутся — доступ через оставшийся способ входа не изменится.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Отмена</AlertDialogCancel>
                <AlertDialogAction onClick={onUnbind}>Отвязать</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : !isTelegramWebApp() ? (
          <TelegramLoginButton onAuth={handleAuth} label="Подключить" className="munin-connect-button" />
        ) : null}
      </div>

      <MergeConflictDialog
        conflict={merge.conflict}
        merging={merge.merging}
        onConfirm={merge.confirm}
        onCancel={merge.cancel}
      />
    </section>
  );
}

function EmailSection({
  connected,
  externalId,
  canUnbind,
  onUnbind,
  unbinding,
}: {
  connected: boolean;
  externalId?: string;
  canUnbind: boolean;
  onUnbind: () => void;
  unbinding: boolean;
}) {
  const [step, setStep] = useState<'idle' | 'email' | 'code'>('idle');
  const [email, setEmail] = useState('');
  const requestCode = useRequestBindEmailCode();
  const confirmEmail = useConfirmBindEmail();
  const merge = useMergeFlow(
    (result) => toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER)),
    () => setStep('idle'),
  );

  const handleSubmitEmail = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestCode.isPending || !email) return;
    try {
      await requestCode.mutateAsync(email);
      setStep('code');
    } catch {
      toast.error('Не получилось отправить код, попробуйте позже');
    }
  };

  // Возвращается без ошибки и при конфликте тоже: код верный, EmailCodeStep
  // не должен считать это неверным кодом и списывать попытку. Решение —
  // перенести или нет — принимается отдельно, в диалоге MergeConflictDialog.
  const verifyAndBind = async (bindEmail: string, code: string) => {
    try {
      const result = await confirmEmail.mutateAsync({ email: bindEmail, code });
      toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER));
      return result;
    } catch (err) {
      if (merge.catchConflict(err)) return undefined;
      throw err;
    }
  };

  return (
    <section className="munin-identity-row">
      <div className="munin-identity-summary">
        <div className="munin-identity-provider">
          <div className="munin-identity-icon">
            <IconMail className="h-5 w-5" />
          </div>
          <div>
            <p className="text-sm font-medium">Почта</p>
            <IdentityState connected={connected}>{connected ? externalId : 'Код в письме'}</IdentityState>
          </div>
        </div>

        {connected ? (
          <AlertDialog>
            <AlertDialogTrigger
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
              disabled={!canUnbind || unbinding}
              title={!canUnbind ? 'Это единственный способ входа' : undefined}
            >
              {unbinding ? 'Отвязываем...' : 'Отвязать'}
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Отвязать почту?</AlertDialogTitle>
                <AlertDialogDescription>
                  Больше нельзя будет войти по этому адресу. Задачи и данные останутся — доступ через оставшийся способ входа не изменится.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Отмена</AlertDialogCancel>
                <AlertDialogAction onClick={onUnbind}>Отвязать</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : step === 'idle' ? (
          <Button variant="outline" size="sm" className="munin-connect-button" onClick={() => setStep('email')}>Подключить</Button>
        ) : null}
      </div>

      {step === 'email' && (
        <form onSubmit={handleSubmitEmail} className="munin-identity-form">
          <Input
            type="email"
            required
            autoFocus
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="почта@пример.ру"
            disabled={requestCode.isPending}
            className="h-10"
            aria-label="Адрес почты"
          />
          <Button type="submit" disabled={requestCode.isPending || !email} className="h-10 shrink-0">
            Продолжить
          </Button>
          <Button type="button" variant="ghost" onClick={() => setStep('idle')} disabled={requestCode.isPending}>Отмена</Button>
        </form>
      )}

      {step === 'code' && (
        <EmailCodeStep
          email={email}
          onBack={() => setStep('email')}
          onVerified={() => setStep('idle')}
          requestCode={(e) => requestCode.mutateAsync(e)}
          verifyCode={verifyAndBind}
        />
      )}

      <MergeConflictDialog
        conflict={merge.conflict}
        merging={merge.merging}
        onConfirm={merge.confirm}
        onCancel={merge.cancel}
      />
    </section>
  );
}

function PhoneSection({
  connected,
  externalId,
  canUnbind,
  onUnbind,
  unbinding,
  available,
}: {
  connected: boolean;
  externalId?: string;
  canUnbind: boolean;
  onUnbind: () => void;
  unbinding: boolean;
  available: boolean;
}) {
  const [step, setStep] = useState<'idle' | 'phone' | 'waiting'>('idle');
  const [phone, setPhone] = useState('');
  const [confirmationNumber, setConfirmationNumber] = useState('');
  const requestConfirmation = useRequestBindPhoneConfirmation();
  const pollConfirmation = usePollBindPhoneConfirmation();
  const cancelConfirmation = useCancelBindPhoneConfirmation();
  const merge = useMergeFlow(
    (result) => toast.success(mergeToastMessage(result.mergedFrom ?? EMPTY_TRANSFER)),
    () => setStep('idle'),
  );

  const handleSubmitPhone = async (e: React.FormEvent) => {
    e.preventDefault();
    if (requestConfirmation.isPending || !phone) return;
    try {
      const result = await requestConfirmation.mutateAsync(phone);
      setConfirmationNumber(result.confirmationNumber);
      setStep('waiting');
    } catch {
      toast.error('Не получилось запросить звонок, попробуйте позже');
    }
  };

  const handleConfirmed = (result: PhoneBindConfirmationStatus) => {
    if (result.status === 'conflict' && result.tasks !== null && result.groups !== null
        && result.tags !== null && result.mergeToken) {
      merge.presentConflict({ tasks: result.tasks, groups: result.groups, tags: result.tags, mergeToken: result.mergeToken });
      setStep('idle');
      return;
    }
    toast.success(mergeToastMessage(EMPTY_TRANSFER));
    setStep('idle');
  };

  return (
    <section className="munin-identity-row">
      <div className="munin-identity-summary">
        <div className="munin-identity-provider">
          <div className="munin-identity-icon">
            <IconPhone className="h-5 w-5" />
          </div>
          <div>
            <p className="text-sm font-medium">Телефон</p>
            <IdentityState connected={connected}>{connected ? externalId : 'Подтверждение звонком'}</IdentityState>
          </div>
        </div>

        {connected ? (
          <AlertDialog>
            <AlertDialogTrigger
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
              disabled={!canUnbind || unbinding}
              title={!canUnbind ? 'Это единственный способ входа' : undefined}
            >
              {unbinding ? 'Отвязываем...' : 'Отвязать'}
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Отвязать телефон?</AlertDialogTitle>
                <AlertDialogDescription>
                  Больше нельзя будет войти по этому номеру. Задачи и данные останутся — доступ через оставшийся способ входа не изменится.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Отмена</AlertDialogCancel>
                <AlertDialogAction onClick={onUnbind}>Отвязать</AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        ) : step === 'idle' && available ? (
          <Button variant="outline" size="sm" className="munin-connect-button" onClick={() => setStep('phone')}>Подключить</Button>
        ) : null}
      </div>

      {step === 'phone' && available && (
        <form onSubmit={handleSubmitPhone} className="munin-identity-form">
          <Input
            type="tel"
            inputMode="tel"
            required
            autoFocus
            value={phone}
            onChange={(e) => setPhone(formatPhoneInput(e.target.value))}
            placeholder="+7 (999) 123-45-67"
            disabled={requestConfirmation.isPending}
            className="h-10"
            aria-label="Номер телефона"
          />
          <Button type="submit" disabled={requestConfirmation.isPending || !phone} className="h-10 shrink-0">
            Продолжить
          </Button>
          <Button type="button" variant="ghost" onClick={() => setStep('idle')} disabled={requestConfirmation.isPending}>Отмена</Button>
        </form>
      )}

      {step === 'waiting' && (
        <PhoneWaitingStep
          phone={phone}
          confirmationNumber={confirmationNumber}
          onBack={() => setStep('phone')}
          onConfirmed={handleConfirmed}
          pollStatus={(p) => pollConfirmation.mutateAsync(p)}
          cancel={(p) => cancelConfirmation.mutateAsync(p)}
        />
      )}

      <MergeConflictDialog
        conflict={merge.conflict}
        merging={merge.merging}
        onConfirm={merge.confirm}
        onCancel={merge.cancel}
      />
    </section>
  );
}
