import { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconFlame, IconBolt, IconSquare, IconClock, IconBell, IconArrowRight, IconPencil } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { fromZonedTime, toZonedTime } from 'date-fns-tz';
import { addDays } from 'date-fns';
import { Priority } from '@/lib/store';
import { formatDeadline, formatReminderTime, getPriorityBgColor, cn } from '@/lib/utils';
import {
  useFocusTasks,
  useUpcomingFocusTasks,
  useCompleteTask,
  useUpdateTask,
  useTasksList,
  usePlannedDateSuggestion,
} from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useDisplayName } from '@/lib/hooks/useSettings';
import { useMinuteTick } from '@/lib/hooks/useMinuteTick';
import { Button } from '@/app/components/ui/button';
import { Card } from '@/app/components/ui/card';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { EmailBindBanner } from '@/app/components/EmailBindBanner';

interface Task {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  plannedDate?: string;
  estimateMinutes?: number;
  groupName?: string;
  reminders?: { id: string; fireAt: string; status: string }[];
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
  timezone: string;
  onComplete: (id: string) => void;
  onSnooze: (id: string) => void;
  onStart: (id: string) => void;
  onClick: (task: Task) => void;
  suggestPlannedDate: boolean;
}

function FocusTaskCard({
  task,
  index,
  timezone,
  onComplete,
  onSnooze,
  onStart,
  onClick,
  suggestPlannedDate,
}: FocusTaskCardProps) {
  const [completing, setCompleting] = useState(false);
  useMinuteTick();
  usePlannedDateSuggestion(
    task.id,
    suggestPlannedDate && !task.deadline && !task.plannedDate,
  );

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
          getPriorityBgColor(task.priority as Priority)
        )}
      >
        {/* Header row */}
        <div className="flex items-start gap-3">
          <div className="flex-shrink-0 mt-0.5">
            {getPriorityIcon(task.priority as Priority)}
          </div>
          <div className="flex-1 min-w-0 space-y-0.5">
            <div className={cn('text-xs font-semibold uppercase tracking-wide', getPriorityBadgeClass(task.priority as Priority))}>
              {getPriorityLabel(task.priority as Priority)}
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
        {(task.deadline || task.groupName || task.estimateMinutes || task.reminders?.length) && (
          <div className="flex flex-wrap gap-x-4 gap-y-1.5 text-sm text-muted-foreground">
            {task.deadline && (
              <div className="flex items-center gap-1.5">
                <IconClock className="h-3.5 w-3.5" />
                <span>{formatDeadline(task.deadline, timezone, task.status)}</span>
              </div>
            )}
            {task.groupName && (
              <div className="flex items-center gap-1">
                <span>{task.groupName}</span>
              </div>
            )}
            {task.estimateMinutes && (
              <div className="flex items-center gap-1">
                <span>~{task.estimateMinutes} мин</span>
              </div>
            )}
            {/* Только ближайшее напоминание, без счётчиков и без красного (Б3) */}
            {task.reminders && task.reminders.length > 0 && (
              <div className="flex items-center gap-1.5">
                <IconBell className="h-3.5 w-3.5" />
                <span>{formatReminderTime(task.reminders[0].fireAt, timezone)}</span>
              </div>
            )}
          </div>
        )}

        {/* Actions */}
        <div className="flex flex-wrap gap-2 pt-1">
          <Button
            onClick={handleComplete}
            className="flex-1 min-w-[110px] h-10 gap-2"
          >
            ✓ Сделано
          </Button>
          {task.status === 'TODO' && (
            <Button
              variant="outline"
              className="flex-1 min-w-[110px] h-10"
              onClick={() => onStart(task.id)}
            >
              Начать
            </Button>
          )}
          <Button
            variant="outline"
            className="flex-1 min-w-[110px] h-10"
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

const TIME_BUDGET_OPTIONS: { label: string; minutes: number }[] = [
  { label: '15 мин', minutes: 15 },
  { label: '30 мин', minutes: 30 },
  { label: '1 час', minutes: 60 },
];

// Подсветка «Сколько времени есть?» — локально у пользователя, серверного
// поля нет: до первого выбора, потом молча гаснет насовсем (В7).
const TIME_BUDGET_USED_KEY = 'taskflow_time_budget_used';

function readTimeBudgetUsed(): boolean {
  try {
    return localStorage.getItem(TIME_BUDGET_USED_KEY) === '1';
  } catch {
    return true; // недоступно хранилище — не навязываем подсветку
  }
}

export function FocusPage() {
  const userName = useDisplayName();
  const [availableMinutes, setAvailableMinutes] = useState<number | undefined>(undefined);
  const [timeBudgetUsed, setTimeBudgetUsed] = useState(readTimeBudgetUsed);

  const markTimeBudgetUsed = () => {
    if (timeBudgetUsed) return;
    setTimeBudgetUsed(true);
    try {
      localStorage.setItem(TIME_BUDGET_USED_KEY, '1');
    } catch {
      // недоступно — переживёт до следующей загрузки, не критично
    }
  };

  const { data: focusTasks = [], isLoading, error } = useFocusTasks(availableMinutes);
  const todayDone = !isLoading && focusTasks.length === 0;
  const { data: upcomingTasks = [], isLoading: upcomingLoading } = useUpcomingFocusTasks(todayDone, availableMinutes);
  const { data: allTasks = [] } = useTasksList();
  const { mutate: completeTask } = useCompleteTask();
  const { mutate: updateTask } = useUpdateTask();
  const { timezone, isReady: timezoneReady } = useUserTimezone();

  const showingUpcoming = todayDone && upcomingTasks.length > 0;
  const tasksToShow = showingUpcoming ? upcomingTasks : focusTasks;

  // Продвижение, а не невыполненное: по исследованию задач без срока,
  // откладывание — способ управления эмоциями, ведущие причины — страх
  // неудачи и перфекционизм. Счётчик невыполненного питает именно эти
  // причины, счётчик сделанного — нет. Три плоских числа за сегодня, без
  // сравнения с планом и без серии дней — просто что случилось.
  const isToday = (dateStr?: string) => {
    if (!dateStr) return false;
    const zoned = toZonedTime(new Date(dateStr), timezone);
    const nowZoned = toZonedTime(new Date(), timezone);
    return (
      zoned.getFullYear() === nowZoned.getFullYear() &&
      zoned.getMonth() === nowZoned.getMonth() &&
      zoned.getDate() === nowZoned.getDate()
    );
  };

  const closedTodayCount = allTasks.filter((t: any) => isToday(t.completedAt)).length;
  const startedTodayCount = allTasks.filter((t: any) => isToday(t.startedAt)).length;
  const movedTodayCount = allTasks.filter((t: any) => isToday(t.plannedDateSetAt)).length;

  const progressParts = [
    closedTodayCount > 0 ? `закрыто ${closedTodayCount}` : null,
    startedTodayCount > 0 ? `начато ${startedTodayCount}` : null,
    movedTodayCount > 0 ? `отложено ${movedTodayCount}` : null,
  ].filter(Boolean);
  const progressLabel = progressParts.length > 0 ? `Сегодня: ${progressParts.join(' · ')}` : 'Все задачи';

  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const today = new Date().toLocaleDateString('ru-RU', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    timeZone: timezone,
  });

  const handleSnooze = (id: string) => {
    const zonedNow = toZonedTime(new Date(), timezone);
    const zonedTomorrow = addDays(zonedNow, 1);
    const wallClockTomorrow9am = new Date(
      zonedTomorrow.getFullYear(),
      zonedTomorrow.getMonth(),
      zonedTomorrow.getDate(),
      9, 0, 0
    );
    const tomorrow9am = fromZonedTime(wallClockTomorrow9am, timezone);
    updateTask({ id, plannedDate: tomorrow9am.toISOString() });
  };

  const handleOpenTask = (task: Task) => {
    setSelectedTask(task);
    setModalOpen(true);
  };

  const handleCompleteTask = (id: string) => {
    completeTask(id);
  };

  const handleStartTask = (id: string) => {
    updateTask({ id, status: 'IN_PROGRESS' });
  };

  if (isLoading || !timezoneReady) {
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

  if (todayDone && upcomingLoading) {
    return (
      <div className="max-w-2xl mx-auto flex items-center justify-center min-h-[60vh]">
        <p className="text-muted-foreground">Смотрим, что дальше...</p>
      </div>
    );
  }

  if (todayDone && upcomingTasks.length === 0) {
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
          <h2 className="text-2xl font-semibold">Всё сделано на сегодня!</h2>
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
        <div className="w-full max-w-md">
          <EmailBindBanner />
        </div>
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
          <Link
            to="/all"
            className="flex-shrink-0 flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors text-right"
          >
            <span>{progressLabel}</span>
            <IconArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>

        <EmailBindBanner />

        {!showingUpcoming && (
          <motion.div
            initial={timeBudgetUsed ? false : { scale: 1.05, opacity: 0.6 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ duration: 0.5, ease: 'easeOut' }}
            className={cn(
              'flex flex-wrap items-center gap-2 rounded-lg -mx-2 px-2 py-1.5 transition-colors',
              !timeBudgetUsed && 'bg-primary/5 ring-1 ring-primary/25'
            )}
          >
            <span className="text-sm text-muted-foreground flex items-center gap-1.5">
              Сколько времени есть?
              {!timeBudgetUsed && (
                <span className="h-1.5 w-1.5 rounded-full bg-primary flex-shrink-0" aria-hidden="true" />
              )}
            </span>
            {TIME_BUDGET_OPTIONS.map((option) => (
              <Button
                key={option.minutes}
                variant={availableMinutes === option.minutes ? 'default' : 'outline'}
                size="sm"
                className="h-7 px-3 text-xs"
                onClick={() => {
                  markTimeBudgetUsed();
                  setAvailableMinutes(availableMinutes === option.minutes ? undefined : option.minutes);
                }}
              >
                {option.label}
              </Button>
            ))}
          </motion.div>
        )}

        {showingUpcoming && (
          <p className="text-sm text-muted-foreground -mt-2">
            Сегодняшний план выполнен — вот что дальше.
          </p>
        )}

        {/* Focus label */}
        <div className="flex items-center gap-2">
          <div className="h-px flex-1 bg-border" />
          <span className="text-xs font-medium text-muted-foreground uppercase tracking-widest px-2">
            {showingUpcoming ? 'Дальше по плану' : 'Фокус сейчас'}
          </span>
          <div className="h-px flex-1 bg-border" />
        </div>

        {/* Task cards */}
        <div className="space-y-3">
          <AnimatePresence>
            {tasksToShow.map((task, index) => (
              <FocusTaskCard
                key={task.id}
                task={task}
                index={index}
                timezone={timezone}
                onComplete={handleCompleteTask}
                onSnooze={handleSnooze}
                onStart={handleStartTask}
                onClick={handleOpenTask}
                suggestPlannedDate={!showingUpcoming}
              />
            ))}
          </AnimatePresence>
        </div>
      </div>

      <TaskDetailModal
        task={selectedTask}
        open={modalOpen}
        showFocusHint={!showingUpcoming}
        onClose={() => setModalOpen(false)}
      />
    </>
  );
}
