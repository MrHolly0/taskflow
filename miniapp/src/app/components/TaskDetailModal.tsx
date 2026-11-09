import { useState, useEffect } from 'react';
import { IconTrash, IconBell, IconBellPlus, IconX, IconRepeat } from '@tabler/icons-react';
import { Priority, Status } from '@/lib/store';
import {
  useUpdateTask,
  type UpdateTaskRequest,
  useDeleteTask,
  useGroups,
  useTaskReminders,
  useAddReminder,
  useCancelReminder,
  useSnoozeReminder,
  useClearRecurrence,
  useFocusHint,
  RecurrenceRule,
} from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import {
  buildReminderPresets,
  isPastReminderTime,
  isoToZonedDate,
  isoToZonedTime,
  zonedInputToIso,
} from '@/lib/reminderPresets';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { DateField } from '@/app/components/DateField';
import { Textarea } from '@/app/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { cn, formatReminderTime } from '@/lib/utils';

const PRIORITY_OPTIONS: { value: Priority; label: string; color: string }[] = [
  { value: 'URGENT', label: 'Срочно', color: 'text-red-500' },
  { value: 'HIGH', label: 'Важно', color: 'text-orange-500' },
  { value: 'MEDIUM', label: 'Средний', color: 'text-blue-500' },
  { value: 'LOW', label: 'Когда будет время', color: 'text-muted-foreground' },
];

const STATUS_OPTIONS: { value: Status; label: string }[] = [
  { value: 'TODO', label: 'К выполнению' },
  { value: 'IN_PROGRESS', label: 'В работе' },
  { value: 'DONE', label: 'Готово' },
  { value: 'CANCELLED', label: 'Отменено' },
];

const NO_RECURRENCE = '__none__';

const RECURRENCE_OPTIONS: { value: string; label: string }[] = [
  { value: NO_RECURRENCE, label: 'Без повтора' },
  { value: 'DAILY', label: 'Каждый день' },
  { value: 'WEEKLY', label: 'Каждую неделю' },
  { value: 'WEEKDAYS', label: 'По будням' },
  { value: 'MONTHLY', label: 'Каждый месяц' },
];

const WEEKDAY_OPTIONS: { value: string; label: string }[] = [
  { value: 'MONDAY', label: 'Пн' },
  { value: 'TUESDAY', label: 'Вт' },
  { value: 'WEDNESDAY', label: 'Ср' },
  { value: 'THURSDAY', label: 'Чт' },
  { value: 'FRIDAY', label: 'Пт' },
  { value: 'SATURDAY', label: 'Сб' },
  { value: 'SUNDAY', label: 'Вс' },
];

export interface TaskInput {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  plannedDate?: string;
  group?: string;
  groupName?: string;
  groupId?: string;
  estimatedTime?: number;
  estimateMinutes?: number;
  recurrence?: RecurrenceRule;
  persistentReminder?: boolean;
}

interface TaskDetailModalProps {
  task: TaskInput | null;
  open: boolean;
  onClose: () => void;
  showFocusHint?: boolean;
  initialSection?: 'reminders' | 'group' | 'deadline' | 'planned';
}

function AddReminderForm({
  taskId,
  timezone,
  onDone,
}: {
  taskId: string;
  timezone: string;
  onDone: () => void;
}) {
  const { mutateAsync: addReminder, isPending } = useAddReminder();
  const [date, setDate] = useState('');
  const [time, setTime] = useState('');
  const [error, setError] = useState('');

  const presets = buildReminderPresets(new Date(), timezone);

  const submit = async (fireAt: Date) => {
    if (isPastReminderTime(fireAt)) {
      setError('Это время уже прошло');
      return;
    }
    try {
      await addReminder({ taskId, fireAt: fireAt.toISOString() });
      onDone();
    } catch {
      setError('Не удалось назначить напоминание. Выбранное время сохранено, попробуй ещё раз.');
    }
  };

  const handleManualSubmit = () => {
    if (!date || !time) return;
    submit(new Date(zonedInputToIso(date, time, timezone)));
  };

  return (
    <div className="space-y-2 rounded-lg border border-border/60 p-3">
      <div className="grid grid-cols-2 gap-1.5">
        {presets.map((p) => (
          <Button
            key={p.key}
            type="button"
            variant="outline"
            size="sm"
            className="h-8 text-xs w-full"
            disabled={isPending}
            onClick={() => submit(p.fireAt)}
          >
            {p.label}
          </Button>
        ))}
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <DateField label="Дата напоминания" value={date} onChange={value => { setDate(value); setError(''); }} />
        <Input
          type="time"
          value={time}
          onChange={(e) => { setTime(e.target.value); setError(''); }}
          className="h-9 text-sm"
          disabled={!date}
        />
      </div>
      {error && <p className="text-xs text-destructive">{error}</p>}
      <div className="flex gap-2">
        <Button type="button" size="sm" className="h-8" disabled={!date || !time || isPending} onClick={handleManualSubmit}>
          Добавить
        </Button>
        <Button type="button" variant="ghost" size="sm" className="h-8" onClick={onDone}>
          Отмена
        </Button>
      </div>
    </div>
  );
}

export function TaskDetailModal({ task, open, onClose, showFocusHint = false, initialSection }: TaskDetailModalProps) {
  const { mutateAsync: updateTask } = useUpdateTask();
  const { mutate: deleteTask } = useDeleteTask();
  const { data: groups = [] } = useGroups();
  const { timezone, isReady: timezoneReady } = useUserTimezone();
  const remindersQuery = useTaskReminders(task?.id, open);
  const reminders = (remindersQuery.data ?? []).filter(reminder => reminder.status === 'PENDING').sort((a, b) => a.fireAt.localeCompare(b.fireAt));
  const nextReminder = reminders.find(reminder => Date.parse(reminder.fireAt) > Date.now());
  const { mutate: cancelReminder } = useCancelReminder();
  const { mutate: snoozeReminder } = useSnoozeReminder();
  const { mutateAsync: clearRecurrence } = useClearRecurrence();
  const { data: focusHint, isLoading: focusHintLoading } = useFocusHint(
    task?.id,
    open && showFocusHint,
  );

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState<Priority>('MEDIUM');
  const [status, setStatus] = useState<Status>('TODO');
  const [persistentReminder, setPersistentReminder] = useState(false);
  const [selectedGroupId, setSelectedGroupId] = useState<string>('');
  const [deadlineDate, setDeadlineDate] = useState('');
  const [plannedDay, setPlannedDay] = useState('');
  const [deadlineTime, setDeadlineTime] = useState('');
  const [estimatedTime, setEstimatedTime] = useState('');
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [showAddReminder, setShowAddReminder] = useState(false);
  const [snoozingReminderId, setSnoozingReminderId] = useState<string | null>(null);
  const [recurrenceType, setRecurrenceType] = useState(NO_RECURRENCE);
  const [recurrenceInterval, setRecurrenceInterval] = useState('1');
  const [recurrenceDays, setRecurrenceDays] = useState<string[]>([]);
  const [recurrenceDayOfMonth, setRecurrenceDayOfMonth] = useState('');
  const [recurrenceEndsAt, setRecurrenceEndsAt] = useState('');

  useEffect(() => {
    if (task) {
      setSaveError('');
      setShowAddReminder(false);
      setSnoozingReminderId(null);
      setTitle(task.title);
      setDescription(task.description ?? '');
      setPriority((task.priority as Priority) ?? 'MEDIUM');
      setStatus((task.status as Status) ?? 'TODO');
      setPersistentReminder(task.persistentReminder ?? false);
      const estimate = task.estimateMinutes ?? task.estimatedTime;
      setEstimatedTime(estimate ? String(estimate) : '');
      // resolve initial group id
      if (task.groupId) {
        setSelectedGroupId(task.groupId);
      } else {
        const groupLabel = task.groupName ?? task.group ?? '';
        const found = groups.find(g => g.name === groupLabel);
        setSelectedGroupId(found?.id ?? '');
      }
      const recurrence = task.recurrence;
      setRecurrenceType(recurrence?.type ?? NO_RECURRENCE);
      setRecurrenceInterval(recurrence?.intervalN ? String(recurrence.intervalN) : '1');
      setRecurrenceDays(recurrence?.daysOfWeek ?? []);
      setRecurrenceDayOfMonth(recurrence?.dayOfMonth ? String(recurrence.dayOfMonth) : '');
    }
  }, [task?.id, open]);

  // Тот же приём, что и с дедлайном чуть ниже: зависит от часового пояса.
  useEffect(() => {
    if (task && timezoneReady) {
      setRecurrenceEndsAt(isoToZonedDate(task.recurrence?.endsAt, timezone));
    }
  }, [task?.id, timezoneReady, timezone]);

  // Отдельный эффект — срок зависит от часового пояса из настроек, который
  // может подгрузиться позже остального. Пока не готов, поля остаются пустыми,
  // а не показывают дату/время, посчитанные в поясе браузера.
  useEffect(() => {
    if (task && timezoneReady) {
      setPlannedDay(isoToZonedDate(task.plannedDate, timezone));
      setDeadlineDate(isoToZonedDate(task.deadline, timezone));
      setDeadlineTime(isoToZonedTime(task.deadline, timezone) || '20:59');
    }
  }, [task?.id, timezoneReady, timezone]);

  useEffect(() => {
    if (!open || !initialSection || !task) return;
    if (initialSection === 'reminders') setShowAddReminder(true);
    const timer = setTimeout(() => {
      const section = document.querySelector<HTMLElement>(`[data-task-section="${initialSection}"]`);
      section?.scrollIntoView({ block: 'nearest' });
      const control = section?.matches('button, input') ? section : section?.querySelector<HTMLElement>('button, input');
      control?.focus({ preventScroll: true });
    }, 0);
    return () => clearTimeout(timer);
  }, [open, task?.id, initialSection]);

  const taskId = task?.id;

  const buildRecurrence = (): RecurrenceRule | undefined => {
    if (recurrenceType === NO_RECURRENCE) return undefined;
    return {
      type: recurrenceType,
      intervalN: recurrenceType === 'DAILY' || recurrenceType === 'WEEKLY'
        ? parseInt(recurrenceInterval) || 1
        : undefined,
      daysOfWeek: recurrenceType === 'WEEKLY' && recurrenceDays.length > 0 ? recurrenceDays : undefined,
      dayOfMonth: recurrenceType === 'MONTHLY' ? parseInt(recurrenceDayOfMonth) || undefined : undefined,
      endsAt: recurrenceEndsAt ? zonedInputToIso(recurrenceEndsAt, '23:59', timezone) : undefined,
    };
  };

  const toggleRecurrenceDay = (day: string) => {
    setRecurrenceDays((prev) => (prev.includes(day) ? prev.filter((d) => d !== day) : [...prev, day]));
  };

  const handleSave = async () => {
    if (!taskId) return;
    setSaving(true);
    try {
      // recurrence в UpdateTaskRequest — null/undefined значит "не менять", как
      // и остальные необязательные поля, поэтому снятие повтора — отдельный
      // вызов, а не часть основного patch.
      if (recurrenceType === NO_RECURRENCE && task?.recurrence) {
        await clearRecurrence(taskId);
      }
      const patch: UpdateTaskRequest = { id: taskId };
      if (title.trim() !== task?.title) patch.title = title.trim();
      if (description !== (task?.description ?? '')) patch.description = description;
      if (priority !== task?.priority) patch.priority = priority;
      if (status !== task?.status) patch.status = status;
      const nextDeadline = deadlineDate ? zonedInputToIso(deadlineDate, deadlineTime || '23:59', timezone) : null;
      if ((nextDeadline ? Date.parse(nextDeadline) : null) !== (task?.deadline ? Date.parse(task.deadline) : null)) patch.deadline = nextDeadline;
      if (plannedDay !== isoToZonedDate(task?.plannedDate, timezone)) patch.plannedDate = plannedDay ? zonedInputToIso(plannedDay, '09:00', timezone) : null;
      if (selectedGroupId !== (task?.groupId ?? '')) patch.groupId = selectedGroupId || null;
      const nextEstimate = estimatedTime ? Number(estimatedTime) : null;
      if (nextEstimate !== (task?.estimateMinutes ?? task?.estimatedTime ?? null)) patch.estimateMinutes = nextEstimate;
      if (persistentReminder !== (task?.persistentReminder ?? false)) patch.persistentReminder = persistentReminder;
      const recurrence = buildRecurrence();
      if (recurrence && JSON.stringify(recurrence) !== JSON.stringify(task?.recurrence)) patch.recurrence = recurrence;
      await updateTask(patch);
      onClose();
    } catch {
      setSaveError('Не удалось сохранить. Изменения остались в форме, попробуй ещё раз.');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = () => {
    if (!taskId) return;
    deleteTask(taskId);
    onClose();
  };

  if (!task) return null;

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        className={cn(
          'sm:max-w-lg w-full p-0 gap-0 flex flex-col',
          'self-start max-h-[calc(100dvh-2rem)]',
          'sm:self-center sm:max-h-[90dvh]'
        )}
      >
        <DialogHeader className="px-6 pt-6">
          <DialogTitle className="sr-only">Задача</DialogTitle>
        </DialogHeader>

        {/* Прокручивается только содержимое — панель действий ниже закреплена (В1) */}
        <div className="flex-1 min-h-0 overflow-y-auto px-6 py-4 space-y-3">
          {/* Title */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Задача
            </label>
            <Input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="text-base font-medium h-11"
              placeholder="Название задачи"
            />
          </div>

          {/* Description */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Описание
            </label>
            <Textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Что нужно сделать..."
              className="resize-none min-h-[80px]"
            />
          </div>

          {showFocusHint && (focusHintLoading || focusHint) && (
            <div className="rounded-lg border border-primary/20 bg-primary/5 px-4 py-3">
              <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Первый шаг
              </p>
              <p className="mt-1 text-sm">
                {focusHintLoading ? 'Подбираем короткий шаг…' : focusHint}
              </p>
            </div>
          )}

          {/* Priority + Status row */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Приоритет
              </label>
              <Select value={priority} onValueChange={(v) => setPriority(v as Priority)}>
                <SelectTrigger className="h-10">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PRIORITY_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      <span className={cn('font-medium', opt.color)}>{opt.label}</span>
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Статус
              </label>
              <Select value={status} onValueChange={(v) => setStatus(v as Status)}>
                <SelectTrigger className="h-10">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {STATUS_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {/* Deadline */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Дедлайн
            </label>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <DateField label="Срок сдачи" section="deadline" value={deadlineDate} onChange={setDeadlineDate} />
              <Input
                type="time"
                value={deadlineTime}
                onChange={(e) => setDeadlineTime(e.target.value)}
                className="h-10 text-sm w-full"
                disabled={!deadlineDate}
              />
            </div>
          </div>

          {/* Напоминания (Б1/Б2) — сразу под дедлайном: то же самое время, что и он,
              а не отдельная тема в конце формы. Независимы от срока, могут стоять
              и на задаче без дедлайна. */}
          <div className="space-y-2" data-task-section="reminders">
            <div className="flex items-center justify-between gap-3 flex-wrap">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Напоминания
              </label>
              {!(taskId && showAddReminder) && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="h-8 gap-1.5 text-xs"
                  onClick={() => setShowAddReminder(true)}
                >
                  <IconBellPlus className="h-3.5 w-3.5" />
                  Напомнить
                </Button>
              )}
            </div>
            <p className="reminder-state" role="status">
              {remindersQuery.isPending ? 'Проверяем напоминания…' : remindersQuery.isError
                ? 'Не удалось проверить напоминания. Попробуй ещё раз.'
                : nextReminder ? `Следующее напоминание: ${formatReminderTime(nextReminder.fireAt, timezone)}`
                : 'Будущих напоминаний нет. Можно назначить отдельное время.'}
            </p>
            {/* Настойчивость (Б1) — ставит человек, не ассистент. */}
            <label className="flex items-center gap-2 text-sm cursor-pointer select-none">
              <input
                type="checkbox"
                checked={persistentReminder}
                onChange={(e) => setPersistentReminder(e.target.checked)}
                className="flex-shrink-0"
              />
              Напоминать повторно, если не отмечу выполненной
            </label>
            {persistentReminder && !reminders.length && <p className="text-sm text-muted-foreground">Сначала назначь первое напоминание. Настойчивый повтор начнётся после него.</p>}
            {reminders.length > 0 && (
              <div className="space-y-1.5">
                {reminders.map((r) => (
                  <div key={r.id} className="rounded-lg border border-border/60 px-3 py-2 space-y-1.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-sm flex items-center gap-1.5">
                        <IconBell className="h-3.5 w-3.5 text-muted-foreground" />
                        {formatReminderTime(r.fireAt, timezone)}
                      </span>
                      <div className="flex items-center gap-1">
                        {/* Б2: у настойчивого повтора это точка решения — предлагаем
                            отложить, не только снять совсем. */}
                        {r.persistent && (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="h-7 text-xs text-muted-foreground"
                            onClick={() => setSnoozingReminderId(snoozingReminderId === r.id ? null : r.id)}
                          >
                            Отложить
                          </Button>
                        )}
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-muted-foreground hover:text-destructive"
                          onClick={() => taskId && cancelReminder({ taskId, reminderId: r.id })}
                        >
                          <IconX className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </div>
                    {snoozingReminderId === r.id && (
                      <div className="grid grid-cols-3 gap-1.5">
                        {buildReminderPresets(new Date(), timezone).map((p) => (
                          <Button
                            key={p.key}
                            type="button"
                            variant="outline"
                            size="sm"
                            className="h-7 text-xs"
                            onClick={() => {
                              if (taskId) {
                                snoozeReminder({ taskId, reminderId: r.id, fireAt: p.fireAt.toISOString() });
                              }
                              setSnoozingReminderId(null);
                            }}
                          >
                            {p.label}
                          </Button>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
            {taskId && showAddReminder && (
              <AddReminderForm taskId={taskId} timezone={timezone} onDone={() => setShowAddReminder(false)} />
            )}
          </div>

          <div className="space-y-1.5">
            <label htmlFor="task-planned-day" className="text-sm font-medium">День работы</label>
            <DateField id="task-planned-day" section="planned" label="День работы" value={plannedDay} onChange={setPlannedDay} />
            <p className="text-sm text-muted-foreground">Когда хочешь заняться задачей. Это не срок сдачи.</p>
          </div>
          {/* Group + Estimate row */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Группа
              </label>
              <Select
                value={selectedGroupId || '__none__'}
                onValueChange={(v) => setSelectedGroupId(v === '__none__' ? '' : v)}
              >
                <SelectTrigger className="h-10" data-task-section="group">
                  <SelectValue placeholder="Без группы" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">Без группы</SelectItem>
                  {groups.map((g) => (
                    <SelectItem key={g.id} value={g.id}>
                      {g.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Оценка (мин)
              </label>
              <Input
                type="number"
                value={estimatedTime}
                onChange={(e) => setEstimatedTime(e.target.value)}
                placeholder="30"
                className="h-10"
              />
            </div>
          </div>

          {/* Повтор — следующее вхождение порождается при закрытии, не по расписанию заранее */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide flex items-center gap-1.5">
              <IconRepeat className="h-3.5 w-3.5" />
              Повтор
            </label>
            <Select value={recurrenceType} onValueChange={setRecurrenceType}>
              <SelectTrigger className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {RECURRENCE_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value} value={opt.value}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>

            {(recurrenceType === 'DAILY' || recurrenceType === 'WEEKLY') && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span>Каждые</span>
                <Input
                  type="number"
                  min="1"
                  value={recurrenceInterval}
                  onChange={(e) => setRecurrenceInterval(e.target.value)}
                  className="h-9 w-16 text-center"
                />
                <span>{recurrenceType === 'DAILY' ? 'дн.' : 'нед.'}</span>
              </div>
            )}

            {recurrenceType === 'WEEKLY' && (
              <div className="flex flex-wrap gap-1.5">
                {WEEKDAY_OPTIONS.map((d) => (
                  <button
                    key={d.value}
                    type="button"
                    onClick={() => toggleRecurrenceDay(d.value)}
                    className={cn(
                      'h-8 w-9 rounded-md text-xs font-medium border transition-colors',
                      recurrenceDays.includes(d.value)
                        ? 'bg-primary text-primary-foreground border-primary'
                        : 'border-border/60 text-muted-foreground hover:bg-accent/40'
                    )}
                  >
                    {d.label}
                  </button>
                ))}
              </div>
            )}

            {recurrenceType === 'MONTHLY' && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span>День месяца</span>
                <Input
                  type="number"
                  min="1"
                  max="31"
                  value={recurrenceDayOfMonth}
                  onChange={(e) => setRecurrenceDayOfMonth(e.target.value)}
                  className="h-9 w-16 text-center"
                />
              </div>
            )}

            {recurrenceType !== NO_RECURRENCE && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span className="flex-shrink-0">Закончить</span>
                <DateField label="Последний день повтора" value={recurrenceEndsAt} onChange={setRecurrenceEndsAt} />
              </div>
            )}
          </div>
        </div>

        {/* Панель действий закреплена внизу модалки — видна без прокрутки (В1) */}
        <div className="flex-shrink-0 flex items-center gap-2 px-6 py-4 border-t border-border/60">
          <Button onClick={handleSave} disabled={saving} className="flex-1 h-10">
            {saving ? 'Сохраняем...' : 'Сохранить'}
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="h-10 w-10 text-muted-foreground hover:text-destructive hover:bg-destructive/10 flex-shrink-0"
            onClick={handleDelete}
          >
            <IconTrash className="h-4 w-4" />
          </Button>
        </div>
        {saveError && <p className="text-sm text-destructive" role="alert">{saveError}</p>}
      </DialogContent>
    </Dialog>
  );
}
