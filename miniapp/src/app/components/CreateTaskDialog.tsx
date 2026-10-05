import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { toast } from 'sonner';
import { useCreateTask, useGroups, type TaskResponse } from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { zonedInputToIso } from '@/lib/reminderPresets';
import { useTaskUi } from '@/lib/task-ui';
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
}: {
  open: boolean;
  onClose: () => void;
  groupId?: string;
  onCreated?: (task: TaskResponse) => void;
  onInspect?: (id: string) => void;
}) {
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<Fields>({
    defaultValues: { title: '', description: '', date: '', time: '', planned: '', minutes: '' },
  });
  const [group, setGroup] = useState(groupId || '__none__');
  const [priority, setPriority] = useState('MEDIUM');
  const create = useCreateTask();
  const { data: groups = [] } = useGroups();
  const { timezone, isReady } = useUserTimezone();
  const reveal = useTaskUi((s) => s.reveal);
  useEffect(() => {
    if (open) {
      reset();
      setGroup(groupId || '__none__');
      setPriority('MEDIUM');
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
    try {
      const task = await create.mutateAsync({
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
  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="munin-form-dialog">
        <DialogHeader>
          <DialogTitle>Новая задача</DialogTitle>
          <DialogDescription>
            Достаточно названия. Остальное можно добавить позже.
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
          <details className="manual-task-options">
            <summary>Даты, оценка и другие детали</summary>
            <div className="munin-form-grid">
              <label className="munin-field">
                Срок
                <Input type="date" {...register('date')} />
              </label>
              <label className="munin-field">
                Время
                <Input type="time" {...register('time')} />
              </label>
              <label className="munin-field">
                День исполнения
                <Input type="date" {...register('planned')} />
              </label>
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
          <div className="flex gap-2">
            <Button type="submit" disabled={create.isPending || !isReady}>
              {create.isPending ? 'Добавляем…' : 'Добавить'}
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
