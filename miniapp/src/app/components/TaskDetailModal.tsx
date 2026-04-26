import { useState, useEffect } from 'react';
import { IconTrash, IconClock, IconX } from '@tabler/icons-react';
import { useStore, Task, Priority, Status, getStatusLabel, getPriorityLabel } from '@/lib/store';
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

interface TaskDetailModalProps {
  task: Task | null;
  open: boolean;
  onClose: () => void;
}

export function TaskDetailModal({ task, open, onClose }: TaskDetailModalProps) {
  const updateTask = useStore((s) => s.updateTask);
  const deleteTask = useStore((s) => s.deleteTask);
  const groups = useStore((s) => s.groups);

  const [title, setTitle] = useState(task?.title ?? '');
  const [description, setDescription] = useState(task?.description ?? '');
  const [priority, setPriority] = useState<Priority>(task?.priority ?? 'MEDIUM');
  const [status, setStatus] = useState<Status>(task?.status ?? 'TODO');
  const [group, setGroup] = useState<string>(task?.group ?? '');
  const [estimatedTime, setEstimatedTime] = useState<string>(
    task?.estimatedTime ? String(task.estimatedTime) : ''
  );

  // Sync state when task changes
  useEffect(() => {
    if (task) {
      setTitle(task.title);
      setDescription(task.description ?? '');
      setPriority(task.priority);
      setStatus(task.status);
      setGroup(task.group ?? '');
      setEstimatedTime(task.estimatedTime ? String(task.estimatedTime) : '');
    }
  }, [task?.id]);

  const taskId = task?.id;

  const handleSave = () => {
    if (!taskId) return;
    updateTask(taskId, {
      title,
      description,
      priority,
      status,
      group: group || undefined,
      estimatedTime: estimatedTime ? parseInt(estimatedTime) : undefined,
    });
    onClose();
  };

  const handleDelete = () => {
    if (!taskId) return;
    deleteTask(taskId);
    onClose();
  };

  const handleOpenChange = (open: boolean) => {
    if (!open) onClose();
  };

  if (!task) return null;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg w-full">
        <DialogHeader>
          <DialogTitle className="sr-only">Задача</DialogTitle>
        </DialogHeader>

        <div className="space-y-5">
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

          {/* Group + Time row */}
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Группа
              </label>
              <Select
                value={group || '__none__'}
                onValueChange={(v) => setGroup(v === '__none__' ? '' : v)}
              >
                <SelectTrigger className="h-10">
                  <SelectValue placeholder="Без группы" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">Без группы</SelectItem>
                  {groups.map((g) => (
                    <SelectItem key={g.id} value={g.name}>
                      {g.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Время (мин)
              </label>
              <div className="relative">
                <IconClock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  type="number"
                  value={estimatedTime}
                  onChange={(e) => setEstimatedTime(e.target.value)}
                  placeholder="30"
                  className="pl-9 h-10"
                />
              </div>
            </div>
          </div>

          {/* Actions */}
          <div className="flex items-center gap-2 pt-1">
            <Button onClick={handleSave} className="flex-1 h-10">
              Сохранить
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