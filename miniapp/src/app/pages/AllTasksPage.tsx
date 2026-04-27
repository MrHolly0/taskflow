import { useState } from 'react';
import { IconSearch, IconPlus, IconClock, IconInbox } from '@tabler/icons-react';
import { Status } from '@/lib/store';
import { formatDeadline, cn } from '@/lib/utils';
import { getStatusLabel } from '@/lib/store';
import { useTasksList } from '@/lib/hooks/useTasks';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { Tabs, TabsList, TabsTrigger } from '@/app/components/ui/tabs';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { QuickInputModal } from '@/app/components/QuickInputModal';

interface Task {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  estimateMinutes?: number;
  isDraft: boolean;
  groupId?: string;
  groupName?: string;
}

type Context = 'now' | 'today' | 'week' | 'all';

const PRIORITY_DOT: Record<string, string> = {
  URGENT: 'bg-red-500',
  HIGH: 'bg-orange-500',
  MEDIUM: 'bg-blue-500',
  LOW: 'bg-gray-400',
};

const STATUS_COLOR: Record<Status, string> = {
  TODO: 'bg-muted text-muted-foreground',
  IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-900/40 dark:text-blue-400',
  DONE: 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-400',
  CANCELLED: 'bg-muted text-muted-foreground line-through',
};

export function AllTasksPage() {
  const [context, setContext] = useState<Context>('today');
  const [search, setSearch] = useState('');
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [taskModalOpen, setTaskModalOpen] = useState(false);
  const [quickInputOpen, setQuickInputOpen] = useState(false);

  const { data: allTasks = [], isLoading, error } = useTasksList();
  const filteredTasks = (allTasks || []).filter((task) => {
    if (search && !task.title.toLowerCase().includes(search.toLowerCase())) {
      return false;
    }
    return true;
  });

  const handleOpenTask = (task: Task) => {
    setSelectedTask(task);
    setTaskModalOpen(true);
  };

  if (isLoading) {
    return (
      <div className="max-w-4xl mx-auto flex items-center justify-center min-h-[60vh]">
        <p className="text-muted-foreground">Загружаем задачи...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto flex items-center justify-center min-h-[60vh]">
        <p className="text-destructive">Ошибка загрузки задач</p>
      </div>
    );
  }

  return (
    <>
      <div className="max-w-4xl mx-auto space-y-5">
        {/* Header */}
        <h1 className="text-2xl font-semibold">Все задачи</h1>

        {/* Filters */}
        <div className="flex flex-col sm:flex-row gap-3">
          <div className="flex items-center gap-2">
            <Tabs value={context} onValueChange={(v) => setContext(v as Context)} className="flex-1 sm:flex-initial">
              <TabsList className="h-9 w-full sm:w-auto gap-1 p-1">
                <TabsTrigger value="now" className="text-xs px-3 cursor-pointer flex-1 sm:flex-initial">Сейчас</TabsTrigger>
                <TabsTrigger value="today" className="text-xs px-3 cursor-pointer flex-1 sm:flex-initial">Сегодня</TabsTrigger>
                <TabsTrigger value="week" className="text-xs px-3 cursor-pointer flex-1 sm:flex-initial">Неделя</TabsTrigger>
                <TabsTrigger value="all" className="text-xs px-3 cursor-pointer flex-1 sm:flex-initial">Всё</TabsTrigger>
              </TabsList>
            </Tabs>
            <Button
              size="icon"
              variant="outline"
              className="h-9 w-9 flex-shrink-0 sm:hidden"
              onClick={() => setQuickInputOpen(true)}
              title="Новая задача"
            >
              <IconPlus className="h-4 w-4" />
            </Button>
          </div>

          <div className="relative flex-1 sm:max-w-60">
            <IconSearch className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
            <Input
              placeholder="Поиск..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9 h-9 text-sm"
            />
          </div>
        </div>

        {/* Count */}
        <div className="text-sm text-muted-foreground">
          {filteredTasks.length} {filteredTasks.length === 1 ? 'задача' : 'задач'}
        </div>

        {/* Tasks list */}
        <div className="space-y-1.5">
          {filteredTasks.map((task) => (
            <button
              key={task.id}
              onClick={() => handleOpenTask(task)}
              className={cn(
                'w-full text-left group cursor-pointer',
                'border border-border/60 rounded-xl p-4 hover:bg-accent/40 transition-colors',
                task.status === 'DONE' && 'opacity-60'
              )}
            >
              <div className="flex items-center gap-3">
                {/* Priority dot */}
                <div
                  className={cn(
                    'w-2 h-2 rounded-full flex-shrink-0 mt-0.5',
                    PRIORITY_DOT[task.priority]
                  )}
                />

                {/* Main content */}
                <div className="flex-1 min-w-0 space-y-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={cn(
                      'font-medium text-sm',
                      task.status === 'DONE' && 'line-through text-muted-foreground'
                    )}>
                      {task.title}
                    </span>
                    <span className={cn(
                      'text-xs px-2 py-0.5 rounded-full font-medium flex-shrink-0',
                      STATUS_COLOR[task.status as Status]
                    )}>
                      {getStatusLabel(task.status as Status)}
                    </span>
                  </div>
                  <div className="flex items-center gap-3 text-xs text-muted-foreground flex-wrap">
                    {task.deadline && (
                      <span className="flex items-center gap-1">
                        <IconClock className="h-3 w-3" />
                        {formatDeadline(task.deadline)}
                      </span>
                    )}
                    {task.groupName && <span>{task.groupName}</span>}
                    {task.estimateMinutes && <span>~{task.estimateMinutes} мин</span>}
                  </div>
                </div>

                {/* Arrow */}
                              </div>
            </button>
          ))}
        </div>

        {filteredTasks.length === 0 && (
          <div className="text-center py-16 space-y-3">
            <IconInbox className="h-10 w-10 mx-auto text-muted-foreground/60" />
            <p className="text-muted-foreground">В этом контексте задач нет</p>
            <Button variant="outline" onClick={() => setQuickInputOpen(true)} className="gap-2">
              <IconPlus className="h-4 w-4" />
              Добавить задачу
            </Button>
          </div>
        )}
      </div>

      <TaskDetailModal
        task={selectedTask}
        open={taskModalOpen}
        onClose={() => setTaskModalOpen(false)}
      />
      <QuickInputModal open={quickInputOpen} onClose={() => setQuickInputOpen(false)} />
    </>
  );
}
