import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import { toast, useSonner } from 'sonner';
import { IconCheck, IconCalendar, IconBell, IconPencil, IconTrash, IconX, IconAlertTriangle } from '@tabler/icons-react';
import type { ApplyResult, Proposal } from '@/lib/hooks/useAssistant';
import { taskDetailQuery, useDeleteTask } from '@/lib/hooks/useTasks';
import { useSettings } from '@/lib/hooks/useSettings';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { getStatusLabel } from '@/lib/store';
import { useTaskUi } from '@/lib/task-ui';
import { formatPlannedDate, formatReminderTime } from '@/lib/utils';
import { plural } from '@/lib/plural';
import { createdTaskIds } from '@/lib/task-receipt';
import { receiptCountdown } from '@/lib/receipt-countdown';

function useReceiptLifetime(dismiss: () => void, paused: boolean) {
  const timer = useRef<ReturnType<typeof receiptCountdown>>();
  const [hidden, setHidden] = useState(document.hidden);
  useEffect(() => {
    const update = () => setHidden(document.hidden);
    document.addEventListener('visibilitychange', update);
    return () => document.removeEventListener('visibilitychange', update);
  }, []);
  useEffect(() => {
    timer.current = receiptCountdown(12000, dismiss);
    return () => timer.current?.dispose();
  }, [dismiss]);
  useEffect(() => {
    if (paused || hidden) timer.current?.pause(); else timer.current?.resume();
  }, [paused, hidden, dismiss]);
}

function ReceiptHeading({ title, warning = false, dismiss }: { title: string; warning?: boolean; dismiss: () => void }) {
  return <header className="task-receipt-heading">
    <span className={warning ? 'receipt-warning' : 'receipt-success'} aria-hidden="true">{warning ? <IconAlertTriangle /> : <IconCheck />}</span>
    <strong>{title}</strong>
    <button type="button" className="receipt-close" aria-label="Закрыть результат" onClick={dismiss}><IconX /></button>
  </header>;
}

function CancelledReceipt({ id }: { id: string | number }) {
  return <div className="task-receipt receipt-cancelled"><ReceiptHeading title="Создание отменено" dismiss={() => toast.dismiss(id)} /></div>;
}

function TaskReceipt({ id, result, proposal }: { id: string | number; result: ApplyResult; proposal: Proposal }) {
  const allIds = createdTaskIds(result, proposal);
  const [cancelled, setCancelled] = useState<string[]>([]);
  const [visibleCount, setVisibleCount] = useState(3);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const receipt = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    // Удаление кнопки после повтора запроса не вызывает blur.
    setFocused(Boolean(receipt.current?.contains(document.activeElement)));
  });
  const [held, setHeld] = useState(false);
  useEffect(() => {
    if (!held) return;
    const release = () => setHeld(false);
    window.addEventListener('pointerup', release, true);
    window.addEventListener('pointercancel', release, true);
    window.addEventListener('blur', release);
    return () => {
      window.removeEventListener('pointerup', release, true);
      window.removeEventListener('pointercancel', release, true);
      window.removeEventListener('blur', release);
    };
  }, [held]);
  const [failure, setFailure] = useState<{ taskId: string; message: string } | null>(null);
  const activeIds = allIds.filter(taskId => !cancelled.includes(taskId));
  const visibleIds = activeIds.slice(0, visibleCount);
  const tasks = useQueries({ queries: visibleIds.map(taskDetailQuery) });
  const remove = useDeleteTask();
  const inspect = useTaskUi(state => state.inspectTask);
  const { timezone, isReady, error: timezoneError, refetch: reloadTimezone } = useUserTimezone();
  const { data: settings } = useSettings();
  const { toasts } = useSonner();
  const behind = toasts.length > 0 && toasts[0].id !== id;
  const dismiss = useCallback(() => { toast.dismiss(id); }, [id]);
  useReceiptLifetime(dismiss, hovered || focused || held || behind || !isReady || remove.isPending || Boolean(failure)
    || tasks.some(task => task.isPending || task.isError));
  const errors = result.outcomes.filter(outcome => !outcome.success);
  const otherCount = result.outcomes.filter(outcome => outcome.success && !allIds.includes(outcome.taskId ?? '')).length;
  const cancel = (taskId: string) => {
    setFailure(null);
    remove.mutate(taskId, {
      onSuccess: () => {
        if (activeIds.length === 1) {
          toast.custom(nextId => <CancelledReceipt id={nextId} />, { id, duration: 5000, closeButton: false, dismissible: true, className: 'task-receipt-toast' });
        } else setCancelled(previous => [...previous, taskId]);
      },
      onError: () => setFailure({ taskId, message: 'Не удалось отменить создание. Задача сохранена — попробуй ещё раз.' }),
    });
  };
  const title = errors.length ? 'Применено не всё' : activeIds.length === 1 ? 'Задача создана'
    : `Создано ${activeIds.length} ${plural(activeIds.length, ['задача', 'задачи', 'задач'])}`;
  return <div ref={receipt} className="task-receipt" onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}
    onPointerDown={() => setHeld(true)} onPointerUp={() => setHeld(false)} onPointerCancel={() => setHeld(false)}
    onFocusCapture={() => setFocused(true)} onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false); }}>
    <ReceiptHeading title={title} warning={errors.length > 0} dismiss={dismiss} />
    <div className="task-receipt-body">
      {timezoneError && !isReady && <div className="receipt-load-error"><p>Не удалось загрузить часовой пояс. Время пока не показываем.</p><button onClick={() => void reloadTimezone()}>Повторить проверку</button></div>}
      {visibleIds.map((taskId, index) => {
        const query = tasks[index];
        const task = query.data;
        const reminders = (task?.reminders ?? []).filter(reminder => reminder.status === 'PENDING')
          .sort((a, b) => Date.parse(a.fireAt) - Date.parse(b.fireAt));
        return <section className="task-receipt-item" key={taskId} aria-busy={query.isPending}>
          {query.isError ? <div className="receipt-load-error"><p>Задача создана, но не удалось прочитать её параметры.</p><button onClick={() => void query.refetch()}>Повторить проверку</button></div>
            : !task || !isReady ? <p className="receipt-loading">Проверяем сохранённые даты…</p> : <>
              <h3>{task.title}</h3>
              <dl className="receipt-facts">
                <div><IconCalendar aria-hidden="true" /><dt>Срок</dt><dd>{task.deadline ? formatReminderTime(task.deadline, timezone) : 'Без срока'}</dd></div>
                <div><IconBell aria-hidden="true" /><dt>Напоминание</dt><dd>{reminders.length ? reminders.map(reminder => <span key={reminder.id}>{formatReminderTime(reminder.fireAt, timezone)}{Date.parse(reminder.fireAt) < Date.now() && ' · время прошло'}</span>) : 'Не назначено'}</dd></div>
                {task.plannedDate && <div><IconCalendar aria-hidden="true" /><dt>День работы</dt><dd>{formatPlannedDate(task.plannedDate, timezone)}</dd></div>}
              </dl>
              {task.status !== 'TODO' && <p className="receipt-group">Статус: {getStatusLabel(task.status as Parameters<typeof getStatusLabel>[0])}</p>}
              {task.groupName && <p className="receipt-group">{task.groupName}</p>}
            </>}
          {failure?.taskId === taskId && <p className="receipt-error" role="alert">{failure.message}</p>}
          <div className="receipt-actions">
            <button type="button" disabled={remove.isPending} onClick={() => { dismiss(); inspect(taskId); }}><IconPencil />Исправить</button>
            <button type="button" disabled={remove.isPending} onClick={() => cancel(taskId)}><IconTrash />{remove.isPending && remove.variables === taskId ? 'Отменяем…' : 'Отменить создание'}</button>
          </div>
        </section>;
      })}
      {activeIds.length > visibleCount && <button className="receipt-more" onClick={() => setVisibleCount(count => count + 3)}>Ещё {activeIds.length - visibleCount} {plural(activeIds.length - visibleCount, ['задача', 'задачи', 'задач'])}</button>}
      {otherCount > 0 && <p className="receipt-group">Также применено {otherCount} {plural(otherCount, ['изменение', 'изменения', 'изменений'])}.</p>}
      {errors.map(outcome => <p className="receipt-error" key={outcome.ordinal}>Не применено: {outcome.summary}. {outcome.error}</p>)}
      {settings && (!settings.notificationsEnabled || !(settings.notifyTelegram || settings.notifyEmail || settings.notifyPush)) && <p className="receipt-group">Доставка уведомлений выключена в настройках.</p>}
    </div>
  </div>;
}

export function showTaskReceipt(result: ApplyResult, proposal: Proposal) {
  if (!createdTaskIds(result, proposal).length) {
    const errors = result.outcomes.filter(outcome => !outcome.success);
    const applied = result.outcomes.filter(outcome => outcome.success).map(outcome => outcome.summary);
    if (errors.length) {
      const description = [...applied.map(summary => `Применено: ${summary}`), ...errors.map(outcome => `Не применено: ${outcome.error || outcome.summary}`)].join('. ');
      toast.warning('Применено не всё', { description, duration: 12000 });
    } else toast.success('Изменения применены', { description: applied.join('. ') });
    return;
  }
  toast.custom(id => <TaskReceipt id={id} result={result} proposal={proposal} />, {
    duration: Infinity, closeButton: false, dismissible: false, className: 'task-receipt-toast',
  });
}
