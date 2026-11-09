import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { toast } from 'sonner';
import { useCreateTask, useUpdateTask, useGroups, type TaskResponse } from '@/lib/hooks/useTasks';
import { getStatusLabel, type Status } from '@/lib/store';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { buildReminderPresets, isoToZonedDate, isoToZonedTime, zonedInputToIso } from '@/lib/reminderPresets';
import { useTaskUi } from '@/lib/task-ui';
import { useSettings } from '@/lib/hooks/useSettings';
import { formatReminderTime } from '@/lib/utils';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/app/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { Input } from '@/app/components/ui/input';
import { Button } from '@/app/components/ui/button';
import { DateField } from '@/app/components/DateField';

const schema = z.object({
  title: z.string().trim().min(1, 'Напиши название').max(512, 'Не больше 512 символов'),
  description: z.string(),
  date: z.string(),
  time: z.string(),
  planned: z.string(),
  minutes: z
    .string()
    .refine(
      (v) => !v || (Number.isInteger(Number(v)) && Number(v) > 0 && Number(v) <= 1440),
      'От 1 до 1440 минут'
    ),
});
type Fields = z.infer<typeof schema>;
export function CreateTaskDialog({
  open,
  onClose,
  groupId,
  onCreated,
  onInspect,
  initialStatus = 'TODO',
}: {
  open: boolean;
  onClose: () => void;
  groupId?: string;
  onCreated?: (task: TaskResponse) => void;
  onInspect?: (id: string) => void;
  initialStatus?: Exclude<Status, 'CANCELLED'>;
}) {
  const {
    register,
    handleSubmit,
    reset,
    setError,
    setValue,
    watch,
    formState: { errors },
  } = useForm<Fields>({
    defaultValues: { title: '', description: '', date: '', time: '', planned: '', minutes: '' },
  });
  const [group, setGroup] = useState(groupId || '__none__');
  const [priority, setPriority] = useState('MEDIUM');
  const [reminderMode, setReminderMode] = useState('auto');
  const [reminderDate, setReminderDate] = useState('');
  const [reminderTime, setReminderTime] = useState('');
  const { data: settings } = useSettings();
  const create = useCreateTask();
  const update = useUpdateTask();
  const { data: groups = [] } = useGroups();
  const { timezone, isReady } = useUserTimezone();
  const reveal = useTaskUi((s) => s.reveal);
  useEffect(() => {
    if (open) {
      reset();
      setGroup(groupId || '__none__');
      setPriority('MEDIUM');
      setReminderMode('auto'); setReminderDate(''); setReminderTime('');
    }
  }, [open, groupId, reset]);
  const submit = handleSubmit(async (values) => {
    const parsed = schema.safeParse(values);
    if (!parsed.success) {
      parsed.error.issues.forEach((i) =>
        setError(i.path[0] as keyof Fields, { message: i.message })
      );
      return;
    }
    if (reminderMode === 'custom' && (!reminderDate || !reminderTime)) {
      toast.error('Выбери дату и время напоминания.'); return;
    }
    const reminderAt = reminderMode === 'custom' ? zonedInputToIso(reminderDate, reminderTime, timezone) : undefined;
    if (reminderAt && Date.parse(reminderAt) <= Date.now()) { toast.error('Время напоминания уже прошло.'); return; }
    try {
      let task = await create.mutateAsync({
        skipDefaultReminders: reminderMode !== 'auto',
        reminderAt,
        title: parsed.data.title,
        description: values.description || undefined,
        priority,
        groupId: group === '__none__' ? undefined : group,
        deadline: values.date
          ? zonedInputToIso(values.date, values.time || '23:59', timezone)
          : undefined,
        plannedDate: values.planned
          ? zonedInputToIso(values.planned, '09:00', timezone)
          : undefined,
        estimateMinutes: values.minutes ? Number(values.minutes) : undefined,
      });
      if (initialStatus !== 'TODO') {
        try {
          task = await update.mutateAsync({ id: task.id, status: initialStatus });
        } catch {
          toast.error('Задача создана в «К выполнению», но статус не изменился.');
        }
      }
      onCreated?.(task);
      reveal([task.id]);
      onClose();
      toast.success(
        onCreated && !task.groupId ? 'Задача добавлена без группы.' : 'Задача добавлена.',
        {
          description: onInspect ? task.title : undefined,
          action: onInspect ? { label: 'Открыть', onClick: () => onInspect(task.id) } : undefined,
        }
      );
    } catch {
      toast.error('Не удалось создать задачу. Введённый текст сохранён.');
    }
  });
  const deadlineDate = watch('date');
  const deadlineTime = watch('time');
  const deadlineInPast = deadlineDate ? Date.parse(zonedInputToIso(deadlineDate, deadlineTime || '23:59', timezone)) <= Date.now() : false;
  const defaultFireAt = deadlineDate && settings && !deadlineInPast
    ? new Date(Math.max(Date.now(), Date.parse(zonedInputToIso(deadlineDate, deadlineTime || '23:59', timezone)) - settings.defaultReminderMinutes * 60000))
    : null;
  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="munin-form-dialog">
        <DialogHeader>
          <DialogTitle>Новая задача</DialogTitle>
          <DialogDescription>
            Достаточно названия. Остальное можно добавить позже.
            {initialStatus !== 'TODO' && ` Добавим в «${getStatusLabel(initialStatus)}».`}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-5">
          <label className="munin-field">
            Название
            <Input
              autoFocus
              {...register('title')}
              placeholder="Что нужно сделать?"
              aria-invalid={Boolean(errors.title)}
            />
            {errors.title && (
              <span className="text-destructive text-xs">{errors.title.message}</span>
            )}
          </label>
          <label className="munin-field">
            Группа
            <Select value={group} onValueChange={setGroup}>
              <SelectTrigger>
                <SelectValue />
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
          </label>
          <div className="munin-form-grid">
            <label className="munin-field">Срок сдачи
              <DateField label="Срок сдачи" value={deadlineDate} onChange={value => setValue('date', value)} />
            </label>
            <label className="munin-field">Время срока
              <Input type="time" {...register('time')} disabled={!deadlineDate} />
              <small>Если время не выбрано — до конца дня.</small>
            </label>
            <label className="munin-field">День работы
              <DateField label="День работы" value={watch('planned')} onChange={value => setValue('planned', value)} />
              <small>Когда хочешь заняться задачей. Это не срок сдачи.</small>
            </label>
          </div>
          <div className="munin-field">
            <span>Напоминание</span>
            <Select value={reminderMode} onValueChange={setReminderMode}>
              <SelectTrigger aria-label="Режим напоминания"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="auto">По настройкам перед сроком</SelectItem>
                <SelectItem value="off">Не напоминать</SelectItem>
                <SelectItem value="custom">Выбрать время</SelectItem>
              </SelectContent>
            </Select>
            {reminderMode === 'auto' && <small>{deadlineInPast ? 'Срок уже прошёл, автоматическое напоминание не назначим.' : defaultFireAt ? `Время напоминания: ${formatReminderTime(defaultFireAt.toISOString(), timezone)}.` : 'Назначим по настройкам, когда укажешь срок.'}</small>}
            {reminderMode !== 'off' && settings && (!settings.notificationsEnabled || !(settings.notifyTelegram || settings.notifyEmail || settings.notifyPush)) && <small>Доставка уведомлений выключена. Включи её в настройках, чтобы получать напоминания.</small>}
            {reminderMode === 'custom' && <>
              <div className="flex flex-wrap gap-2">{buildReminderPresets(new Date(), timezone).map(preset => <Button key={preset.key} type="button" variant="outline" onClick={() => { setReminderDate(isoToZonedDate(preset.fireAt.toISOString(), timezone)); setReminderTime(isoToZonedTime(preset.fireAt.toISOString(), timezone)); }}>{preset.label}</Button>)}</div>
              <div className="munin-form-grid">
                <DateField label="Дата напоминания" value={reminderDate} onChange={setReminderDate} />
                <Input aria-label="Время напоминания" type="time" value={reminderTime} onChange={event => setReminderTime(event.target.value)} />
              </div>
            </>}
          </div>
          <details className="manual-task-options">
            <summary>Оценка, приоритет и описание</summary>
            <div className="munin-form-grid">
              <label className="munin-field">
                Оценка, мин
                <Input type="number" min="1" max="1440" {...register('minutes')} />
                {errors.minutes && (
                  <span className="text-destructive text-xs">{errors.minutes.message}</span>
                )}
              </label>
              <label className="munin-field">
                Приоритет
                <Select value={priority} onValueChange={setPriority}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {[
                      ['LOW', 'Когда будет время'],
                      ['MEDIUM', 'Обычный'],
                      ['HIGH', 'Важно'],
                      ['URGENT', 'Срочно'],
                    ].map(([v, l]) => (
                      <SelectItem key={v} value={v}>
                        {l}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </label>
              <label className="munin-field">
                Описание
                <Input {...register('description')} />
              </label>
            </div>
          </details>
          <div className="manual-task-footer">
            <Button type="submit" disabled={create.isPending || update.isPending || !isReady}>
              {create.isPending || update.isPending ? 'Добавляем…' : 'Добавить'}
            </Button>
            <Button type="button" variant="outline" onClick={onClose}>
              Отмена
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
