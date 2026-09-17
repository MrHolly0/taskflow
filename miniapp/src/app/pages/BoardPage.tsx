import { useEffect, useRef, useState } from 'react';
import { Status, Task } from '@/lib/store';
import { useTasksList, useUpdateTask } from '@/lib/hooks/useTasks';
import {
  CollisionDetection,
  DndContext,
  DragEndEvent,
  DragStartEvent,
  DragOverlay,
  pointerWithin,
  rectIntersection,
  MouseSensor,
  TouchSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import {
  IconClock,
  IconGripVertical,
  IconPlus,
  IconCircleDashed,
  IconProgress,
  IconCircleCheck,
  IconArrowsVertical,
  IconSwipe,
  IconChevronLeft,
  IconChevronRight,
  IconBell,
} from '@tabler/icons-react';
import { formatDeadline, formatReminderTime, cn } from '@/lib/utils';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useMinuteTick } from '@/lib/hooks/useMinuteTick';
import { Badge } from '@/app/components/ui/badge';
import { Card } from '@/app/components/ui/card';
import { Button } from '@/app/components/ui/button';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { QuickInputModal } from '@/app/components/QuickInputModal';

const PRIORITY_DOT: Record<string, string> = {
  URGENT: 'bg-red-500',
  HIGH: 'bg-orange-500',
  MEDIUM: 'bg-blue-500',
  LOW: 'bg-gray-400',
};

/**
 * Статус определяется по положению пальца внутри области — актуально для длинных
 * секций, у которых closestCorners требовал тащить далеко за границу. Если палец
 * не над одной из секций (например, ещё не долетел до соседней в горизонтальном
 * режиме), запасной вариант — пересечение прямоугольников.
 */
const collisionDetection: CollisionDetection = (args) => {
  const pointerCollisions = pointerWithin(args);
  if (pointerCollisions.length > 0) {
    return pointerCollisions;
  }
  return rectIntersection(args);
};

const COLUMN_STYLE: Record<Status, string> = {
  TODO: 'border-t-slate-400',
  IN_PROGRESS: 'border-t-blue-500',
  DONE: 'border-t-green-500',
  CANCELLED: 'border-t-muted-foreground',
};

const COLUMNS: {
  id: Status;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  iconColor: string;
}[] = [
  { id: 'TODO', label: 'К выполнению', icon: IconCircleDashed, iconColor: 'text-slate-500' },
  { id: 'IN_PROGRESS', label: 'В работе', icon: IconProgress, iconColor: 'text-blue-500' },
  { id: 'DONE', label: 'Готово', icon: IconCircleCheck, iconColor: 'text-green-500' },
];

/* ────────────── Draggable Task Card ────────────── */
function DraggableTaskCard({
  task,
  isDragging: _isDragging,
  onClick,
}: {
  task: Task;
  isDragging?: boolean;
  onClick: (task: Task) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: task.id,
  });
  const { timezone, isReady: timezoneReady } = useUserTimezone();
  useMinuteTick();

  const style = transform
    ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` }
    : undefined;

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn('touch-none select-none', isDragging && 'opacity-40')}
      {...attributes}
      {...listeners}
    >
      <Card
        className="p-3.5 space-y-2.5 cursor-pointer hover:shadow-md transition-shadow border border-border/60 group"
        onClick={() => !isDragging && onClick(task)}
      >
        <div className="flex items-start gap-2">
          {/* Ручка — визуальная подсказка, зажатие работает с любой точки карточки */}
          <div className="mt-0.5 opacity-0 group-hover:opacity-40 [@media(pointer:coarse)]:opacity-40 transition-opacity cursor-grab active:cursor-grabbing flex-shrink-0">
            <IconGripVertical className="h-4 w-4 text-muted-foreground" />
          </div>

          <div className="flex-1 min-w-0 space-y-1.5">
            <div className="flex items-center gap-1.5">
              <div className={cn('w-1.5 h-1.5 rounded-full flex-shrink-0', PRIORITY_DOT[task.priority])} />
              <span className="text-sm font-medium leading-tight">{task.title}</span>
            </div>

            <div className="flex flex-wrap gap-2 items-center">
              {task.deadline && timezoneReady && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <IconClock className="h-3 w-3" />
                  {formatDeadline(task.deadline, timezone, task.status)}
                </span>
              )}
              {task.group && (
                <span className="text-xs text-muted-foreground">{task.group}</span>
              )}
              {/* Значок напоминания (Б3) — только ближайшее время, без счётчиков и без красного */}
              {task.nextReminderAt && timezoneReady && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <IconBell className="h-3 w-3" />
                  {formatReminderTime(task.nextReminderAt, timezone)}
                </span>
              )}
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}

/* ────────────── Overlay Card (while dragging) ────────────── */
function OverlayTaskCard({ task }: { task: Task }) {
  return (
    <Card className="p-3.5 shadow-xl border border-primary/30 bg-background/95 rotate-1 cursor-grabbing">
      <div className="flex items-center gap-1.5">
        <div className={cn('w-1.5 h-1.5 rounded-full flex-shrink-0', PRIORITY_DOT[task.priority])} />
        <span className="text-sm font-medium">{task.title}</span>
      </div>
    </Card>
  );
}

/* ────────────── Droppable Column ────────────── */
function DroppableColumn({
  column,
  tasks,
  onTaskClick,
  onAddTask,
}: {
  column: (typeof COLUMNS)[number];
  tasks: Task[];
  onTaskClick: (task: Task) => void;
  onAddTask: () => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: column.id });
  const ColumnIcon = column.icon;

  return (
    <div
      ref={setNodeRef}
      className={cn(
        'flex flex-col rounded-xl border-t-2 bg-muted/30 p-3 transition-colors min-h-[200px]',
        COLUMN_STYLE[column.id],
        isOver && 'bg-accent/60 ring-1 ring-primary/30'
      )}
    >
      {/* Column header */}
      <div className="flex items-center justify-between mb-3 px-0.5">
        <div className="flex items-center gap-2">
          <ColumnIcon className={cn('h-4 w-4', column.iconColor)} />
          <h2 className="font-semibold text-sm">{column.label}</h2>
          <Badge variant="secondary" className="h-5 px-1.5 text-xs">
            {tasks.length}
          </Badge>
        </div>
        <Button
          variant="ghost"
          size="icon"
          className="h-6 w-6 text-muted-foreground hover:text-foreground"
          onClick={onAddTask}
        >
          <IconPlus className="h-3.5 w-3.5" />
        </Button>
      </div>

      {/* Tasks */}
      <div className="flex flex-col gap-2 flex-1">
        {tasks.map((task) => (
          <DraggableTaskCard key={task.id} task={task} onClick={onTaskClick} />
        ))}

        {tasks.length === 0 && (
          <div className="flex-1 flex items-center justify-center rounded-lg border border-dashed border-border/60 py-8">
            <p className="text-xs text-muted-foreground">Перетащи сюда</p>
          </div>
        )}
      </div>
    </div>
  );
}

/* ────────────── Узкий экран: статусы друг под другом или по одному со свайпом ────────────── */
type BoardView = 'vertical' | 'horizontal';
const BOARD_VIEW_KEY = 'taskflow.boardView';
const CAROUSEL_GAP = 12; // px, соответствует gap-3 у контейнера карусели

function readBoardView(): BoardView {
  return localStorage.getItem(BOARD_VIEW_KEY) === 'horizontal' ? 'horizontal' : 'vertical';
}

function useIsNarrow(): boolean {
  const [narrow, setNarrow] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(max-width: 767px)').matches
  );
  useEffect(() => {
    const mql = window.matchMedia('(max-width: 767px)');
    const handler = () => setNarrow(mql.matches);
    handler();
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);
  return narrow;
}

/* ────────────── Карусель «один статус на экран» ────────────── */
function SingleStatusView({
  tasksByStatus,
  onTaskClick,
  onAddTask,
  dragActive,
}: {
  tasksByStatus: Record<Status, Task[]>;
  onTaskClick: (task: Task) => void;
  onAddTask: () => void;
  dragActive: boolean;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState(0);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    let frame: number;
    const onScroll = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const first = el.children[0] as HTMLElement | undefined;
        if (!first) return;
        const step = first.offsetWidth + CAROUSEL_GAP;
        const i = Math.round(el.scrollLeft / step);
        setIndex(Math.min(COLUMNS.length - 1, Math.max(0, i)));
      });
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => {
      el.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(frame);
    };
  }, []);

  const goTo = (i: number) => {
    const el = containerRef.current;
    const first = el?.children[0] as HTMLElement | undefined;
    if (!el || !first) return;
    const clamped = Math.min(COLUMNS.length - 1, Math.max(0, i));
    el.scrollTo({ left: (first.offsetWidth + CAROUSEL_GAP) * clamped, behavior: 'smooth' });
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-center gap-3">
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7"
          disabled={index === 0}
          onClick={() => goTo(index - 1)}
        >
          <IconChevronLeft className="h-4 w-4" />
        </Button>
        <div className="flex items-center gap-1.5">
          {COLUMNS.map((c, i) => (
            <span
              key={c.id}
              className={cn('h-1.5 w-1.5 rounded-full', i === index ? 'bg-primary' : 'bg-muted-foreground/30')}
            />
          ))}
        </div>
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7"
          disabled={index === COLUMNS.length - 1}
          onClick={() => goTo(index + 1)}
        >
          <IconChevronRight className="h-4 w-4" />
        </Button>
      </div>
      <div
        ref={containerRef}
        className={cn(
          'flex gap-3 overflow-x-auto pb-1 -mx-4 px-4',
          dragActive ? 'snap-none' : 'snap-x snap-mandatory'
        )}
      >
        {COLUMNS.map((column) => (
          <div key={column.id} className="w-[87%] shrink-0 snap-center">
            <DroppableColumn
              column={column}
              tasks={tasksByStatus[column.id]}
              onTaskClick={onTaskClick}
              onAddTask={onAddTask}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

function toTask(t: any): Task {
  return {
    id: String(t.id),
    title: t.title,
    description: t.description,
    priority: t.priority,
    status: t.status,
    deadline: t.deadline,
    group: t.groupName,
    groupId: t.groupId,
    estimatedTime: t.estimateMinutes,
    createdAt: t.createdAt,
    completedAt: t.completedAt,
    nextReminderAt: t.reminders?.[0]?.fireAt,
  };
}

/* ────────────── BoardPage ────────────── */
export function BoardPage() {
  const { data: apiTasks = [] } = useTasksList();
  const { mutate: updateTaskStatus } = useUpdateTask();

  const tasks: Task[] = apiTasks.map(toTask);

  const [activeTask, setActiveTask] = useState<Task | null>(null);
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [taskModalOpen, setTaskModalOpen] = useState(false);
  const [quickInputOpen, setQuickInputOpen] = useState(false);
  const [boardView, setBoardViewState] = useState<BoardView>(readBoardView);
  const isNarrow = useIsNarrow();

  const setBoardView = (view: BoardView) => {
    localStorage.setItem(BOARD_VIEW_KEY, view);
    setBoardViewState(view);
  };

  const sensors = useSensors(
    useSensor(MouseSensor, {
      activationConstraint: { distance: 8 },
    }),
    useSensor(TouchSensor, {
      activationConstraint: { delay: 200, tolerance: 5 },
    })
  );

  const handleDragStart = (event: DragStartEvent) => {
    const task = tasks.find((t) => t.id === event.active.id);
    setActiveTask(task ?? null);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    setActiveTask(null);
    if (!over) return;

    const taskId = active.id as string;
    const newStatus = over.id as Status;

    const task = tasks.find((t) => t.id === taskId);
    if (task && task.status !== newStatus) {
      updateTaskStatus({ id: taskId, status: newStatus });
    }
  };

  const handleTaskClick = (task: Task) => {
    setSelectedTask(task);
    setTaskModalOpen(true);
  };

  const tasksByStatus: Record<Status, Task[]> = {
    TODO: tasks.filter((t) => t.status === 'TODO'),
    IN_PROGRESS: tasks.filter((t) => t.status === 'IN_PROGRESS'),
    DONE: tasks.filter((t) => t.status === 'DONE'),
    CANCELLED: tasks.filter((t) => t.status === 'CANCELLED'),
  };

  return (
    <>
      <div className="max-w-7xl mx-auto space-y-5">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Доска</h1>
          <Button
            variant="outline"
            size="icon"
            className="md:hidden h-8 w-8"
            onClick={() => setBoardView(boardView === 'vertical' ? 'horizontal' : 'vertical')}
            title={boardView === 'vertical' ? 'Показать по одному статусу, со свайпом' : 'Показать статусы друг под другом'}
          >
            {boardView === 'vertical' ? <IconSwipe className="h-4 w-4" /> : <IconArrowsVertical className="h-4 w-4" />}
          </Button>
        </div>

        <DndContext
          sensors={sensors}
          collisionDetection={collisionDetection}
          autoScroll={{ threshold: { x: 0.25, y: 0.2 } }}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
        >
          {isNarrow ? (
            boardView === 'vertical' ? (
              <div className="flex flex-col gap-4">
                {COLUMNS.map((column) => (
                  <DroppableColumn
                    key={column.id}
                    column={column}
                    tasks={tasksByStatus[column.id]}
                    onTaskClick={handleTaskClick}
                    onAddTask={() => setQuickInputOpen(true)}
                  />
                ))}
              </div>
            ) : (
              <SingleStatusView
                tasksByStatus={tasksByStatus}
                onTaskClick={handleTaskClick}
                onAddTask={() => setQuickInputOpen(true)}
                dragActive={activeTask !== null}
              />
            )
          ) : (
            <div className="grid grid-cols-3 gap-4">
              {COLUMNS.map((column) => (
                <DroppableColumn
                  key={column.id}
                  column={column}
                  tasks={tasksByStatus[column.id]}
                  onTaskClick={handleTaskClick}
                  onAddTask={() => setQuickInputOpen(true)}
                />
              ))}
            </div>
          )}

          <DragOverlay>
            {activeTask && <OverlayTaskCard task={activeTask} />}
          </DragOverlay>
        </DndContext>
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