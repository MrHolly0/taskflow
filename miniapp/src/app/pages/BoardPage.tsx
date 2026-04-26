import { useState } from 'react';
import { useStore, Status, Task } from '@/lib/store';
import {
  DndContext,
  DragEndEvent,
  DragStartEvent,
  DragOverlay,
  closestCorners,
  PointerSensor,
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
} from '@tabler/icons-react';
import { formatDeadline, cn } from '@/lib/utils';
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

  const style = transform
    ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` }
    : undefined;

  return (
    <div ref={setNodeRef} style={style} className={cn(isDragging && 'opacity-40')}>
      <Card
        className="p-3.5 space-y-2.5 cursor-pointer hover:shadow-md transition-shadow border border-border/60 group"
        onClick={() => !isDragging && onClick(task)}
      >
        <div className="flex items-start gap-2">
          {/* Drag handle */}
          <button
            {...attributes}
            {...listeners}
            className="mt-0.5 opacity-0 group-hover:opacity-40 hover:!opacity-100 transition-opacity cursor-grab active:cursor-grabbing flex-shrink-0 touch-none"
            onClick={(e) => e.stopPropagation()}
          >
            <IconGripVertical className="h-4 w-4 text-muted-foreground" />
          </button>

          <div className="flex-1 min-w-0 space-y-1.5">
            <div className="flex items-center gap-1.5">
              <div className={cn('w-1.5 h-1.5 rounded-full flex-shrink-0', PRIORITY_DOT[task.priority])} />
              <span className="text-sm font-medium leading-tight">{task.title}</span>
            </div>

            <div className="flex flex-wrap gap-2 items-center">
              {task.deadline && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <IconClock className="h-3 w-3" />
                  {formatDeadline(task.deadline)}
                </span>
              )}
              {task.group && (
                <span className="text-xs text-muted-foreground">{task.group}</span>
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

/* ────────────── BoardPage ────────────── */
export function BoardPage() {
  const tasks = useStore((state) => state.tasks);
  const updateTask = useStore((state) => state.updateTask);

  const [activeTask, setActiveTask] = useState<Task | null>(null);
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [taskModalOpen, setTaskModalOpen] = useState(false);
  const [quickInputOpen, setQuickInputOpen] = useState(false);

  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: { distance: 8 },
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
      updateTask(taskId, {
        status: newStatus,
        ...(newStatus === 'DONE' ? { completedAt: new Date().toISOString() } : {}),
      });
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
                  </div>

        <DndContext
          sensors={sensors}
          collisionDetection={closestCorners}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
        >
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
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