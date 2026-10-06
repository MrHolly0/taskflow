import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  DndContext, DragOverlay, MouseSensor, TouchSensor, KeyboardSensor,
  useSensor, useSensors, useDraggable, useDroppable, pointerWithin, rectIntersection,
  defaultDropAnimationSideEffects,
  type CollisionDetection, type DragEndEvent, type DragStartEvent,
  type KeyboardCoordinateGetter,
} from '@dnd-kit/core';
import {
  IconClock, IconGripVertical, IconPlus, IconCircleDashed, IconProgress,
  IconCircleCheck, IconChevronLeft, IconChevronRight, IconBell, IconSearch, IconX,
} from '@tabler/icons-react';
import { toast } from 'sonner';
import { useTasksList, useUpdateTask, type TaskResponse } from '@/lib/hooks/useTasks';
import { groupBoardTasks, isBoardStatus, type BoardStatus } from '@/lib/board';
import { formatDeadline, formatReminderTime, cn } from '@/lib/utils';
import { plural } from '@/lib/plural';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useMinuteTick } from '@/lib/hooks/useMinuteTick';
import { useTaskUi, useTaskReveal } from '@/lib/task-ui';
import { Button } from '@/app/components/ui/button';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { CreateTaskDialog } from '@/app/components/CreateTaskDialog';
import { TaskActionMenu } from '@/app/components/TaskRow';
import '@/styles/board.css';

const COLUMNS = [
  { id: 'TODO', label: 'К выполнению', icon: IconCircleDashed },
  { id: 'IN_PROGRESS', label: 'В работе', icon: IconProgress },
  { id: 'DONE', label: 'Готово', icon: IconCircleCheck },
] as const;
type Column = (typeof COLUMNS)[number];
type BoardView = 'vertical' | 'horizontal';
const BOARD_VIEW_KEY = 'taskflow.boardView';
const CANCEL_ID = 'board-cancel';

// Для мыши и пальца принимаем только область под указателем, включая отмену.
const collisionDetection: CollisionDetection = args => {
  const collisions = args.pointerCoordinates ? pointerWithin(args) : rectIntersection(args);
  const cancel = collisions.find(collision => collision.id === CANCEL_ID);
  return cancel ? [cancel] : collisions;
};

const keyboardCoordinates: KeyboardCoordinateGetter = (event, { context, currentCoordinates }) => {
  const direction = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.code];
  if (!direction) return;
  event.preventDefault();
  const current = context.over?.id ?? context.active?.data.current?.status;
  const index = COLUMNS.findIndex(column => column.id === current);
  const next = COLUMNS[Math.max(0, Math.min(2, index + direction))];
  const rect = context.droppableRects.get(next.id);
  const active = context.collisionRect;
  if (!rect || !active) return;
  return {
    x: currentCoordinates.x + rect.left + rect.width / 2 - active.left - active.width / 2,
    y: currentCoordinates.y + rect.top + rect.height / 2 - active.top - active.height / 2,
  };
};

function CardContents({ task, moreButton }: { task: TaskResponse; moreButton?: React.ReactNode }) {
  const { timezone, isReady } = useUserTimezone();
  const reminder = task.reminders?.find(item => item.status === 'PENDING') ?? task.reminders?.[0];
  return (
    <div className="board-task-main">
      <IconGripVertical className="board-task-grip" aria-hidden="true" />
      <div className="board-task-content">
        <p className="board-task-title">{task.title}</p>
        <div className="board-task-meta">
          {task.priority === 'HIGH' && <span>Важно</span>}
          {task.priority === 'URGENT' && <span>Срочно</span>}
          {task.deadline && isReady && <span><IconClock />{formatDeadline(task.deadline, timezone, task.status)}</span>}
          {task.groupName && <span>{task.groupName}</span>}
          {reminder && isReady && <span><IconBell />{formatReminderTime(reminder.fireAt, timezone)}</span>}
        </div>
      </div>
      {moreButton}
    </div>
  );
}

function DraggableTaskCard({ task, onOpen }: { task: TaskResponse; onOpen: () => void }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: task.id, data: { status: task.status },
  });
  const revealRef = useTaskReveal(task.id);
  return (
    <TaskActionMenu task={task} onOpen={onOpen} moreButtonClassName="board-task-more" contextDisabled={isDragging}>
      {({ moreButton, menuOpen }) => (
        <article
          ref={node => { setNodeRef(node); revealRef.current = node; }}
          {...attributes} {...listeners}
          aria-label={task.title}
          aria-describedby="board-interaction-hint"
          data-board-task-id={task.id}
          className={cn('board-task', isDragging && 'board-drag-source', menuOpen && 'board-menu-open')}
          onClick={() => !isDragging && onOpen()}
          onKeyDown={event => {
            if (event.target === event.currentTarget && event.key === 'Enter') {
              event.preventDefault();
              onOpen();
            } else listeners?.onKeyDown?.(event);
          }}
        >
          <CardContents task={task} moreButton={moreButton} />
        </article>
      )}
    </TaskActionMenu>
  );
}

function DroppableColumn({ column, tasks, narrow, searching, onOpen, onAdd }: {
  column: Column; tasks: TaskResponse[]; narrow: boolean; searching: boolean;
  onOpen: (id: string) => void; onAdd: (status: BoardStatus) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: column.id });
  const batch = narrow ? 4 : 8;
  const [extra, setExtra] = useState(0);
  const Icon = column.icon;
  const visible = tasks.slice(0, batch + extra);
  const remaining = tasks.length - visible.length;
  return (
    <section ref={setNodeRef} data-status={column.id}
      aria-label={column.label} className={cn('board-column', isOver && 'board-drop-target')}>
      <div className="board-column-head">
        <h2><Icon className="board-status-icon" /><span>{column.label}</span><small aria-label={`${tasks.length} ${plural(tasks.length, ['задача', 'задачи', 'задач'])}`}>{tasks.length}</small></h2>
        <button className="board-icon-button" aria-label={`Добавить задачу: ${column.label}`}
          onClick={() => onAdd(column.id)}><IconPlus /></button>
      </div>
      <div className="board-column-body">
        {visible.map(task => <DraggableTaskCard key={task.id} task={task} onOpen={() => onOpen(task.id)} />)}
        {!tasks.length && <div className="board-empty-column">
          <p>{searching ? 'Ничего не найдено' : 'Здесь пока нет задач'}</p>
          {!searching && <button onClick={() => onAdd(column.id)}>Добавить задачу</button>}
        </div>}
        {remaining > 0 && <button className="board-load-more" onClick={() => setExtra(value => value + batch)}>
          Ещё {Math.min(batch, remaining)} из {remaining}
        </button>}
      </div>
    </section>
  );
}

function CancelDropZone() {
  const { setNodeRef, isOver } = useDroppable({ id: CANCEL_ID });
  return <div ref={setNodeRef} className={cn('board-cancel-drop', isOver && 'is-over')}>
    <IconX /><div><strong>Отменить перенос</strong><small>Отпусти здесь или нажми Esc</small></div>
  </div>;
}

function useIsNarrow() {
  const [narrow, setNarrow] = useState(() => matchMedia('(max-width: 767px)').matches);
  useEffect(() => {
    const media = matchMedia('(max-width: 767px)');
    const change = () => setNarrow(media.matches);
    media.addEventListener('change', change);
    return () => media.removeEventListener('change', change);
  }, []);
  return narrow;
}

export function BoardPage() {
  const { data: tasks = [], isLoading, error, refetch } = useTasksList();
  const update = useUpdateTask();
  const queryClient = useQueryClient();
  const [activeTask, setActiveTask] = useState<TaskResponse | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createStatus, setCreateStatus] = useState<BoardStatus | null>(null);
  const [search, setSearch] = useState('');
  const [leadingId, setLeadingId] = useState<string>();
  const [boardView, setBoardView] = useState<BoardView>(() =>
    localStorage.getItem(BOARD_VIEW_KEY) === 'horizontal' ? 'horizontal' : 'vertical');
  const [index, setIndex] = useState(0);
  const narrow = useIsNarrow();
  const horizontal = narrow && boardView === 'horizontal';
  const containerRef = useRef<HTMLDivElement>(null);
  const previous = useRef<Map<string, string>>();
  const reveal = useTaskUi(state => state.reveal);
  const revealedAt = useTaskUi(state => state.revealedAt);
  const revealIds = useTaskUi(state => state.revealIds);
  useMinuteTick();

  const goTo = (next: number) => {
    const container = containerRef.current;
    const first = container?.children[0] as HTMLElement | undefined;
    if (!container || !first) return;
    container.scrollTo({ left: (first.offsetWidth + 12) * next,
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
    setIndex(next);
  };

  useEffect(() => {
    if (isLoading) return;
    if (previous.current) {
      const changed = tasks.find(task => isBoardStatus(task.status) &&
        (!previous.current!.has(task.id) || previous.current!.get(task.id) !== task.status));
      if (changed) {
        setLeadingId(changed.id);
        reveal([changed.id]);
      }
    }
    previous.current = new Map(tasks.map(task => [task.id, task.status]));
  }, [tasks, isLoading, reveal]);

  useEffect(() => {
    if (!revealedAt || Date.now() - revealedAt > 8000) return;
    const task = tasks.find(item => item.id === revealIds[0]);
    if (!task || !isBoardStatus(task.status)) return;
    setLeadingId(task.id);
    setSearch('');
    if (horizontal) goTo(COLUMNS.findIndex(column => column.id === task.status));
  }, [revealedAt, revealIds, tasks, horizontal]);

  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 8 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 200, tolerance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: keyboardCoordinates,
      keyboardCodes: { start: ['Space'], cancel: ['Escape'], end: ['Space'] } }),
  );
  const tasksByStatus = groupBoardTasks(tasks, search, leadingId);
  const handleDragStart = ({ active }: DragStartEvent) => {
    setActiveTask(tasks.find(task => task.id === active.id) ?? null);
  };
  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    setActiveTask(null);
    const task = tasks.find(item => item.id === active.id);
    if (!task || !over || !isBoardStatus(String(over.id)) || task.status === over.id) return;
    const status = String(over.id);
    setLeadingId(task.id);
    void queryClient.cancelQueries({ queryKey: ['tasks', 'list'] });
    queryClient.setQueryData<TaskResponse[]>(['tasks', 'list'], items =>
      items?.map(item => item.id === task.id ? { ...item, status } : item));
    update.mutate({ id: task.id, status }, {
      onSuccess: () => {
        toast.success(`Задача перенесена: ${COLUMNS.find(column => column.id === status)?.label}.`, {
          action: { label: 'Отменить', onClick: () => update.mutate({ id: task.id, status: task.status }, {
            onError: () => toast.error('Не удалось вернуть задачу. Попробуй ещё раз.'),
          }) },
        });
      },
      onError: () => {
        queryClient.setQueryData<TaskResponse[]>(['tasks', 'list'], items =>
          items?.map(item => item.id === task.id ? task : item));
        void queryClient.invalidateQueries({ queryKey: ['tasks'] });
        toast.error('Не удалось перенести задачу. Она осталась в прежнем статусе.');
      },
    });
  };

  return <>
    <div className="munin-page munin-board-page">
      <header className="munin-page-head">
        <div><h1>Доска</h1><p className="board-summary">{tasks.filter(task => task.status === 'TODO' || task.status === 'IN_PROGRESS').length} активных · {tasks.filter(task => task.status === 'DONE').length} готово{search.trim() && ' · поиск'}</p></div>
        <Button onClick={() => setCreateStatus('TODO')}><IconPlus className="h-4 w-4" />Задача</Button>
      </header>
      <div className="board-workbar">
        <label className="munin-search"><IconSearch /><input aria-label="Найти задачу на доске" placeholder="Найти задачу" value={search} onChange={event => setSearch(event.target.value)} />
          {search && <button aria-label="Сбросить поиск" className="board-search-reset" onClick={() => setSearch('')}><IconX /></button>}
        </label>
        {narrow && <div className="board-view-switch" aria-label="Вид доски">
          {([['vertical', 'Списком'], ['horizontal', 'По статусам']] as const).map(([value, label]) =>
            <button key={value} aria-pressed={boardView === value} onClick={() => {
              localStorage.setItem(BOARD_VIEW_KEY, value); setBoardView(value); setIndex(0);
            }}>{label}</button>)}
        </div>}
        <p id="board-interaction-hint" className="board-hint">{narrow ? '⋯: быстрые действия' : 'ПКМ или ⋯: быстрые действия'}</p>
      </div>
      {isLoading ? <div className="page-state" role="status">Загружаем задачи…</div> : error ?
        <div className="page-state" role="alert"><p>Не удалось загрузить доску</p><Button variant="outline" onClick={() => void refetch()}>Попробовать снова</Button></div> :
        <DndContext sensors={sensors} collisionDetection={collisionDetection}
          autoScroll={{ threshold: { x: 0.25, y: 0.2 } }}
          onDragStart={handleDragStart} onDragEnd={handleDragEnd} onDragCancel={() => setActiveTask(null)}
          accessibility={{ screenReaderInstructions: { draggable: 'Пробел: взять задачу. Стрелки: выбрать статус. Пробел: перенести. Escape: отменить.' },
            announcements: {
              onDragStart: ({ active }) => `Задача взята: ${tasks.find(task => task.id === active.id)?.title ?? ''}`,
              onDragOver: ({ over }) => over ? (over.id === CANCEL_ID ? 'Отменить перенос' : COLUMNS.find(column => column.id === over.id)?.label) : 'Вне колонок. Перенос отменится.',
              onDragEnd: ({ over }) => over && isBoardStatus(String(over.id)) ? 'Задача отпущена в колонке.' : 'Перенос отменён.',
              onDragCancel: () => 'Перенос отменён.',
            } }}>
          {horizontal && <nav className="board-carousel-nav" aria-label="Статус задач">
            <button aria-label="Предыдущий статус" disabled={!index} onClick={() => goTo(index - 1)}><IconChevronLeft /></button>
            <div>{COLUMNS.map((column, position) => <button key={column.id} aria-pressed={index === position} onClick={() => goTo(position)}>{column.label}</button>)}</div>
            <button aria-label="Следующий статус" disabled={index === 2} onClick={() => goTo(index + 1)}><IconChevronRight /></button>
          </nav>}
          <div ref={containerRef} className={cn('munin-board', horizontal && 'board-horizontal', activeTask && 'board-drag-active')}
            onScroll={event => {
              if (!horizontal) return;
              const first = event.currentTarget.children[0] as HTMLElement | undefined;
              if (first) setIndex(Math.max(0, Math.min(2, Math.round(event.currentTarget.scrollLeft / (first.offsetWidth + 12)))));
            }}>
            {COLUMNS.map(column => <DroppableColumn key={column.id} column={column} narrow={narrow} searching={Boolean(search.trim())}
              tasks={tasksByStatus[column.id]} onOpen={setSelectedId} onAdd={setCreateStatus} />)}
          </div>
          {activeTask && <CancelDropZone />}
          <DragOverlay dropAnimation={matchMedia('(prefers-reduced-motion: reduce)').matches ? null : {
            duration: 180, easing: 'cubic-bezier(.22,1,.36,1)',
            sideEffects: defaultDropAnimationSideEffects({ styles: { active: { opacity: '0' } } }),
          }}>
            {activeTask && <div className="board-task board-drag-overlay"><CardContents task={activeTask} /></div>}
          </DragOverlay>
        </DndContext>}
      <p className="board-note">{narrow ? 'Статус можно изменить в меню ⋯. Для переноса удерживай карточку.' : 'Перетаскивай карточку целиком. С клавиатуры: пробел, стрелки и пробел; Esc — отмена.'}</p>
    </div>
    <TaskDetailModal task={tasks.find(task => task.id === selectedId) ?? null} open={Boolean(selectedId)} onClose={() => setSelectedId(null)} />
    <CreateTaskDialog open={createStatus !== null} initialStatus={createStatus ?? 'TODO'} onClose={() => setCreateStatus(null)} onInspect={setSelectedId} />
  </>;
}
