import { useState, useEffect } from 'react';
import { IconTrash, IconClock } from '@tabler/icons-react';
import { Priority, Status } from '@/lib/store';
import { useUpdateTask, useDeleteTask, useGroups } from '@/lib/hooks/useTasks';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
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
import { cn } from '@/lib/utils';

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

export interface TaskInput {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  group?: string;
  groupName?: string;
  groupId?: string;
  estimatedTime?: number;
  estimateMinutes?: number;
}

interface TaskDetailModalProps {
  task: TaskInput | null;
  open: boolean;
  onClose: () => void;
}

function toDatetimeLocal(iso?: string): string {
  if (!iso) return '';
  return iso.slice(0, 16);
}

export function TaskDetailModal({ task, open, onClose }: TaskDetailModalProps) {
  const { mutateAsync: updateTask } = useUpdateTask();
  const { mutate: deleteTask } = useDeleteTask();
  const { data: groups = [] } = useGroups();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState<Priority>('MEDIUM');
  const [status, setStatus] = useState<Status>('TODO');
  const [selectedGroupId, setSelectedGroupId] = useState<string>('');
  const [deadline, setDeadline] = useState('');
  const [estimatedTime, setEstimatedTime] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (task) {
      setTitle(task.title);
      setDescription(task.description ?? '');
      setPriority((task.priority as Priority) ?? 'MEDIUM');
      setStatus((task.status as Status) ?? 'TODO');
      setDeadline(toDatetimeLocal(task.deadline));
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
    }
  }, [task?.id, groups.length]);

  const taskId = task?.id;

  const handleSave = async () => {
    if (!taskId) return;
    setSaving(true);
    try {
      await updateTask({
        id: taskId,
        title: title.trim() || undefined,
        description: description || undefined,
        priority,
        status,
        deadline: deadline ? `${deadline}:00Z` : undefined,
        groupId: selectedGroupId || undefined,
        estimateMinutes: estimatedTime ? parseInt(estimatedTime) : undefined,
      });
      onClose();
    } catch {
      onClose();
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
      <DialogContent className="sm:max-w-lg w-full max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="sr-only">Задача</DialogTitle>
        </DialogHeader>

        <div className="space-y-3">
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

          {/* Priority + Status row */}
          <div className="grid grid-cols-2 gap-3">
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
            <div className="relative">
              <IconClock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input
                type="datetime-local"
                value={deadline}
                onChange={(e) => setDeadline(e.target.value)}
                className="pl-9 h-10 text-sm"
              />
            </div>
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
                <SelectTrigger className="h-10">
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

          {/* Actions */}
          <div className="flex items-center gap-2 pt-1">
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
        </div>
      </DialogContent>
    </Dialog>
  );
}
