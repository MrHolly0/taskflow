import { useState } from 'react';
import { IconPlus, IconTrash, IconCheck, IconDots, IconArrowRight } from '@tabler/icons-react';
import { GroupResponse, useTasksList, useCompleteTask, useDeleteTask, useUpdateTask, useCreateTask, useDeleteGroup, useGroups } from '@/lib/hooks/useTasks';
import { getGroupIcon } from '@/lib/group-icons';
import { getStatusLabel } from '@/lib/store';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/app/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';

interface GroupDetailModalProps {
  group: GroupResponse | null;
  open: boolean;
  onClose: () => void;
}

function getPriorityDot(priority: string) {
  const colors: Record<string, string> = {
    URGENT: 'bg-red-500',
    HIGH: 'bg-orange-500',
    MEDIUM: 'bg-blue-500',
    LOW: 'bg-gray-400',
  };
  return colors[priority] ?? 'bg-gray-400';
}

export function GroupDetailModal({ group, open, onClose }: GroupDetailModalProps) {
  const { data: allTasks = [] } = useTasksList();
  const { data: groups = [] } = useGroups();
  const { mutate: completeTask } = useCompleteTask();
  const { mutate: deleteTask } = useDeleteTask();
  const { mutate: updateTask } = useUpdateTask();
  const { mutate: createTask } = useCreateTask();
  const { mutate: deleteGroup } = useDeleteGroup();

  const [newTaskTitle, setNewTaskTitle] = useState('');
  const [openedTaskId, setOpenedTaskId] = useState<string | null>(null);

  if (!group) return null;
  const GroupIcon = getGroupIcon(group.icon ?? 'briefcase');

  const groupTasks = allTasks.filter((t) => t.groupId === group.id || t.groupName === group.name);
  const activeTasks = groupTasks.filter((t) => t.status !== 'DONE' && t.status !== 'CANCELLED');
  const doneTasks = groupTasks.filter((t) => t.status === 'DONE');

  const openedTask = openedTaskId ? allTasks.find((t) => t.id === openedTaskId) ?? null : null;

  const handleAddTask = () => {
    if (!newTaskTitle.trim()) return;
    createTask({
      title: newTaskTitle.trim(),
      priority: 'MEDIUM',
      groupId: group.id,
    });
    setNewTaskTitle('');
  };

  const handleDeleteGroup = () => {
    deleteGroup(group.id);
    onClose();
  };

  const otherGroups = groups.filter((g) => g.id !== group.id);

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-md w-full max-h-[90vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center flex-shrink-0">
              <GroupIcon className="h-5 w-5" />
            </div>
            <span>{group.name}</span>
            <span className="ml-auto text-sm font-normal text-muted-foreground">
              {activeTasks.length} активных
            </span>
          </DialogTitle>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto space-y-4 min-h-0">
          {activeTasks.length > 0 && (
            <div className="space-y-2">
              {activeTasks.map((task) => (
                <TaskRow
                  key={task.id}
                  task={task}
                  otherGroups={otherGroups}
                  onOpen={() => setOpenedTaskId(task.id)}
                  onComplete={() => completeTask(task.id)}
                  onMove={(targetGroupId) => updateTask({ id: task.id, groupId: targetGroupId ?? null })}
                  onDelete={() => deleteTask(task.id)}
                />
              ))}
            </div>
          )}

          {doneTasks.length > 0 && (
            <div className="space-y-2">
              <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
                Выполнено
              </p>
              {doneTasks.map((task) => (
                <TaskRow
                  key={task.id}
                  task={task}
                  otherGroups={otherGroups}
                  onOpen={() => setOpenedTaskId(task.id)}
                  onMove={(targetGroupId) => updateTask({ id: task.id, groupId: targetGroupId ?? null })}
                  onDelete={() => deleteTask(task.id)}
                  done
                />
              ))}
            </div>
          )}

          {groupTasks.length === 0 && (
            <p className="text-center py-6 text-muted-foreground text-sm">
              В этой группе пока нет задач
            </p>
          )}
        </div>

        <div className="border-t border-border pt-4 space-y-3 flex-shrink-0">
          <div className="flex gap-2">
            <Input
              value={newTaskTitle}
              onChange={(e) => setNewTaskTitle(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleAddTask()}
              placeholder="Добавить задачу..."
              className="h-10"
            />
            <Button onClick={handleAddTask} disabled={!newTaskTitle.trim()} size="icon" className="h-10 w-10 flex-shrink-0">
              <IconPlus className="h-4 w-4" />
            </Button>
          </div>
          <Button
            variant="ghost"
            className="w-full text-muted-foreground hover:text-destructive hover:bg-destructive/10 h-9 text-sm gap-2"
            onClick={handleDeleteGroup}
          >
            <IconTrash className="h-4 w-4" />
            Удалить группу
          </Button>
        </div>
      </DialogContent>

      <TaskDetailModal
        task={openedTask}
        open={!!openedTask}
        onClose={() => setOpenedTaskId(null)}
      />
    </Dialog>
  );
}

function TaskRow({
  task,
  otherGroups,
  onOpen,
  onComplete,
  onMove,
  onDelete,
  done,
}: {
  task: { id: string; title: string; priority: string; status: string };
  otherGroups: GroupResponse[];
  onOpen: () => void;
  onComplete?: () => void;
  onMove: (targetGroupId: string | undefined) => void;
  onDelete: () => void;
  done?: boolean;
}) {
  return (
    <div
      className={cn(
        'flex items-center gap-3 p-3 rounded-lg border border-border/50',
        done ? 'opacity-60' : 'hover:bg-accent/50 transition-colors'
      )}
    >
      <div className={cn('w-2 h-2 rounded-full flex-shrink-0', getPriorityDot(task.priority))} />
      <button
        onClick={onOpen}
        className={cn(
          'flex-1 text-sm truncate text-left cursor-pointer hover:underline underline-offset-2',
          done && 'line-through'
        )}
      >
        {task.title}
      </button>
      <span className="text-xs text-muted-foreground flex-shrink-0">
        {getStatusLabel(task.status as any)}
      </span>
      {!done && onComplete && (
        <button
          onClick={onComplete}
          title="Отметить выполненной"
          className="flex-shrink-0 w-7 h-7 rounded-md hover:bg-green-500/15 flex items-center justify-center text-muted-foreground hover:text-green-600 dark:hover:text-green-400 transition-colors cursor-pointer"
        >
          <IconCheck className="h-4 w-4" />
        </button>
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            title="Действия"
            className="flex-shrink-0 w-7 h-7 rounded-md hover:bg-accent flex items-center justify-center text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
          >
            <IconDots className="h-4 w-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuSub>
            <DropdownMenuSubTrigger className="gap-2 cursor-pointer">
              <IconArrowRight className="h-4 w-4" />
              Переместить
            </DropdownMenuSubTrigger>
            <DropdownMenuSubContent>
              <DropdownMenuLabel className="text-xs text-muted-foreground font-normal">
                В группу
              </DropdownMenuLabel>
              {otherGroups.length === 0 && (
                <DropdownMenuItem disabled>Нет других групп</DropdownMenuItem>
              )}
              {otherGroups.map((g) => (
                <DropdownMenuItem
                  key={g.id}
                  onClick={() => onMove(g.id)}
                  className="cursor-pointer"
                >
                  {g.name}
                </DropdownMenuItem>
              ))}
              <DropdownMenuSeparator />
              <DropdownMenuItem onClick={() => onMove(undefined)} className="cursor-pointer">
                Без группы
              </DropdownMenuItem>
            </DropdownMenuSubContent>
          </DropdownMenuSub>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={onDelete}
            className="gap-2 cursor-pointer text-destructive focus:text-destructive"
          >
            <IconTrash className="h-4 w-4" />
            Удалить
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
