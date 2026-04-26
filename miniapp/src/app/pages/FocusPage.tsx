import { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconFlame, IconBolt, IconSquare, IconClock, IconArrowRight, IconPencil } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { useStore, Priority } from '@/lib/store';
import { formatDeadline, getPriorityBgColor, cn } from '@/lib/utils';
import { useFocusTasks, useCompleteTask } from '@/lib/hooks/useTasks';
import { Button } from '@/app/components/ui/button';
import { Card } from '@/app/components/ui/card';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';

interface Task {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  estimateMinutes?: number;
  isDraft: boolean;
  groupName?: string;
}

function getPriorityIcon(priority: Priority) {
  switch (priority) {
    case 'URGENT':
      return <IconFlame className="h-4 w-4 text-red-500" />;
    case 'HIGH':
      return <IconBolt className="h-4 w-4 text-orange-500" />;
    case 'MEDIUM':
      return <IconBolt className="h-4 w-4 text-blue-500" />;
    case 'LOW':
      return <IconSquare className="h-4 w-4 text-muted-foreground" />;
  }
}

function getPriorityBadgeClass(priority: Priority): string {
  switch (priority) {
    case 'URGENT':
      return 'text-red-600 dark:text-red-400';
    case 'HIGH':
      return 'text-orange-600 dark:text-orange-400';
    case 'MEDIUM':
      return 'text-blue-600 dark:text-blue-400';
    case 'LOW':
      return 'text-muted-foreground';
  }
}

function getPriorityLabel(priority: Priority): string {
  switch (priority) {
    case 'URGENT': return 'СРОЧНО';
    case 'HIGH': return 'ВАЖНО';
    case 'MEDIUM': return 'Средний';
    case 'LOW': return 'Когда будет время';
  }
}

interface FocusTaskCardProps {
  task: Task;
  index: number;
  onComplete: (id: string) => void;
  onSnooze: (id: string) => void;
  onClick: (task: Task) => void;
}

function FocusTaskCard({ task, index, onComplete, onSnooze, onClick }: FocusTaskCardProps) {
  const [completing, setCompleting] = useState(false);

  const handleComplete = () => {
    setCompleting(true);
    setTimeout(() => onComplete(task.id), 350);
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: completing ? 0 : 1, y: completing ? -10 : 0, scale: completing ? 0.97 : 1 }}
      transition={{ duration: completing ? 0.3 : 0.4, delay: completing ? 0 : index * 0.1 }}
    >
      <Card
        className={cn(
          'p-5 space-y-4 transition-shadow hover:shadow-md border border-border/60',
          getPriorityBgColor(task.priority)
        )}
      >
        {/* Header row */}
        <div className="flex items-start gap-3">
          <div className="flex-shrink-0 mt-0.5">
            {getPriorityIcon(task.priority)}
          </div>
          <div className="flex-1 min-w-0 space-y-0.5">
            <div className={cn('text-xs font-semibold uppercase tracking-wide', getPriorityBadgeClass(task.priority))}>
              {getPriorityLabel(task.priority)}
            </div>
            <button
              onClick={() => onClick(task)}
              className="text-left block w-full cursor-pointer"
            >
              <h3 className="text-lg font-semibold leading-tight hover:underline underline-offset-2 cursor-pointer">
                {task.title}
              </h3>
            </button>
          </div>
        </div>

        {/* Meta info */}
        {(task.deadline || task.group || task.estimatedTime) && (
          <div className="flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-muted-foreground">
            {task.deadline && (
              <div className="flex items-center gap-1.5">
                <IconClock className="h-3.5 w-3.5" />
                <span>{formatDeadline(task.deadline)}</span>
              </div>
            )}
            {task.group && (
              <div className="flex items-center gap-1">
                <span>{task.group}</span>
              </div>
            )}
            {task.estimatedTime && (
              <div className="flex items-center gap-1">
                <span>~{task.estimatedTime} мин</span>
              </div>
            )}
          </div>
        )}

        {/* Actions */}
        <div className="flex gap-2 pt-1">
          <Button
            onClick={handleComplete}
            className="flex-1 h-10 gap-2"
          >
            ✓ Сделано
          </Button>
          <Button
            variant="outline"
            className="flex-1 h-10"
            onClick={() => onSnooze(task.id)}
          >
            Отложить
          </Button>
          <Button
            variant="outline"
            size="icon"
            className="h-10 w-10 flex-shrink-0"
            onClick={() => onClick(task)}
            title="Редактировать"
          >
            <IconPencil className="h-4 w-4" />
          </Button>
        </div>
      </Card>
    </motion.div>
  );
}

export function FocusPage() {
  const user = useStore((state) => state.user);
  const { data: focusTasks = [], isLoading, error } = useFocusTasks();
  const { mutate: completeTask } = useCompleteTask();

  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const userName = user?.name ?? 'Друг';
  const today = new Date().toLocaleDateString('ru-RU', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });

  const handleSnooze = (id: string) => {
    // TODO: реализовать перенесение задачи
  };

  const handleOpenTask = (task: Task) => {
    setSelectedTask(task);
    setModalOpen(true);
  };

  const handleCompleteTask = (id: string) => {
    completeTask(id);
  };

  if (isLoading) {
    return (
      <div className="max-w-2xl mx-auto flex items-center justify-center min-h-[60vh]">
        <p className="text-muted-foreground">Загружаем задачи...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="max-w-2xl mx-auto flex items-center justify-center min-h-[60vh]">
        <p className="text-destructive">Ошибка загрузки задач</p>
      </div>
    );
  }

  if (focusTasks.length === 0) {
    return (
      <div className="max-w-lg mx-auto flex flex-col items-center justify-center min-h-[60vh] space-y-6 text-center px-4">
        <motion.div
          initial={{ scale: 0.5, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={{ type: 'spring', duration: 0.6 }}
          className="text-6xl"
        >
          ✨
        </motion.div>
        <div className="space-y-2">
          <h2 className="text-2xl font-semibold">Всё сделано!</h2>
          <p className="text-muted-foreground">
            Отдыхай или закинь мысли на потом.
          </p>
        </div>
        <Link to="/all">
          <Button size="lg" variant="outline" className="gap-2 h-12 px-6">
            Посмотреть все задачи
            <IconArrowRight className="h-4 w-4" />
          </Button>
        </Link>
      </div>
    );
  }

  return (
    <>
      <div className="max-w-2xl mx-auto space-y-6">
        {/* Header */}
        <div className="flex items-start justify-between">
          <div className="space-y-0.5">
            <h1 className="text-2xl font-semibold">Привет, {userName}!</h1>
            <p className="text-muted-foreground text-sm capitalize">{today}</p>
          </div>
          {remainingCount > 0 && (
            <Link
              to="/all"
              className="flex-shrink-0 flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              <span>Ещё {remainingCount}</span>
              <IconArrowRight className="h-3.5 w-3.5" />
            </Link>
          )}
        </div>

        {/* Focus label */}
        <div className="flex items-center gap-2">
          <div className="h-px flex-1 bg-border" />
          <span className="text-xs font-medium text-muted-foreground uppercase tracking-widest px-2">
            Фокус сейчас
          </span>
          <div className="h-px flex-1 bg-border" />
        </div>

        {/* Task cards */}
        <div className="space-y-3">
          <AnimatePresence>
            {focusTasks.map((task, index) => (
              <FocusTaskCard
                key={task.id}
                task={task}
                index={index}
                onComplete={handleCompleteTask}
                onSnooze={handleSnooze}
                onClick={handleOpenTask}
              />
            ))}
          </AnimatePresence>
        </div>
      </div>

      <TaskDetailModal
        task={selectedTask}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
      />
    </>
  );
}
