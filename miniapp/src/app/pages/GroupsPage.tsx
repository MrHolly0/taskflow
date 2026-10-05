import { useEffect, useRef, useState } from 'react';
import * as ContextMenu from '@radix-ui/react-context-menu';
import * as Dropdown from '@radix-ui/react-dropdown-menu';
import {
  DndContext,
  DragOverlay,
  MouseSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  useDraggable,
  useDroppable,
  pointerWithin,
  rectIntersection,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
} from '@dnd-kit/core';
import {
  IconPlus,
  IconSearch,
  IconDots,
  IconGripVertical,
  IconArrowLeft,
  IconX,
  IconPencil,
  IconTrash,
} from '@tabler/icons-react';
import { toast } from 'sonner';
import {
  useTasksList,
  useGroups,
  useUpdateTask,
  useDeleteGroup,
  type GroupResponse,
  type TaskResponse,
} from '@/lib/hooks/useTasks';
import { useTaskUi, isActiveTask } from '@/lib/task-ui';
import { cn } from '@/lib/utils';
import { Button } from '@/app/components/ui/button';
import { GroupEditor, GroupEmblem } from '@/app/components/GroupEditor';
import { CreateTaskDialog } from '@/app/components/CreateTaskDialog';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { TaskRow } from '@/app/components/TaskRow';
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from '@/app/components/ui/alert-dialog';

function GroupMenu({
  group,
  onEdit,
  onDelete,
  kind,
}: {
  group: GroupResponse;
  onEdit: () => void;
  onDelete: () => void;
  kind: 'context' | 'dropdown';
}) {
  const Menu = kind === 'context' ? ContextMenu : Dropdown;
  return (
    <>
      <Menu.Label className="task-menu-label">
        {group.name}
        <small>Управление группой</small>
      </Menu.Label>
      <Menu.Item className="task-menu-item" onSelect={onEdit}>
        <IconPencil />
        Изменить группу
      </Menu.Item>
      <Menu.Item className="task-menu-item text-destructive" onSelect={onDelete}>
        <IconTrash />
        Удалить группу
      </Menu.Item>
    </>
  );
}
function GroupTile({
  group,
  count,
  selected,
  onOpen,
  onEdit,
  onDelete,
  dragging,
}: {
  group: GroupResponse;
  count: number;
  selected: boolean;
  onOpen: () => void;
  onEdit: () => void;
  onDelete: () => void;
  dragging: boolean;
}) {
  const { setNodeRef, isOver } = useDroppable({
    id: `group-${group.id}`,
    data: { groupId: group.id, kind: 'tile' },
  });
  return (
    <ContextMenu.Root>
      <ContextMenu.Trigger asChild>
        <div
          ref={setNodeRef}
          className={cn(
            'group-tile',
            selected && 'is-selected',
            dragging && isOver && 'is-drag-target'
          )}
        >
          <button className="group-tile-open" aria-expanded={selected} onClick={onOpen}>
            <GroupEmblem group={group} />
            <span className="group-count">{count}</span>
            <strong>{group.name}</strong>
            <small>{count ? 'в планах' : 'пока пусто'}</small>
          </button>
          <Dropdown.Root>
            <Dropdown.Trigger asChild>
              <button className="group-tile-more" aria-label={`Управлять группой ${group.name}`}>
                <IconDots />
              </button>
            </Dropdown.Trigger>
            <Dropdown.Portal>
              <Dropdown.Content className="task-menu" align="start">
                <GroupMenu kind="dropdown" group={group} onEdit={onEdit} onDelete={onDelete} />
              </Dropdown.Content>
            </Dropdown.Portal>
          </Dropdown.Root>
        </div>
      </ContextMenu.Trigger>
      <ContextMenu.Portal>
        <ContextMenu.Content className="task-menu">
          <GroupMenu kind="context" group={group} onEdit={onEdit} onDelete={onDelete} />
        </ContextMenu.Content>
      </ContextMenu.Portal>
    </ContextMenu.Root>
  );
}
function DraggableTask({
  task,
  onOpen,
  disabled,
}: {
  task: TaskResponse;
  onOpen: () => void;
  disabled: boolean;
}) {
  const { setNodeRef, listeners, attributes, isDragging } = useDraggable({
    id: task.id,
    data: { taskId: task.id },
    disabled,
  });
  return (
    <div ref={setNodeRef} className={cn(isDragging && 'group-task-source')}>
      <TaskRow
        task={task}
        variant="folder"
        onOpen={onOpen}
        dragHandle={
          !disabled && (
            <button
              className="group-drag-handle"
              {...listeners}
              {...attributes}
              aria-label={`Перетащить: ${task.title}`}
            >
              <IconGripVertical />
            </button>
          )
        }
      />
    </div>
  );
}
function CancelDrop({ active }: { active: boolean }) {
  const { setNodeRef, isOver } = useDroppable({
    id: 'cancel',
    disabled: !active,
    data: { kind: 'cancel' },
  });
  return active ? (
    <div ref={setNodeRef} className={cn('group-cancel-drop', isOver && 'is-over')}>
      <IconX />
      <div>
        <strong>Отменить перенос</strong>
        <small>Отпусти здесь или нажми Esc</small>
      </div>
    </div>
  ) : null;
}
function Preview({ group, tasks }: { group: GroupResponse; tasks: TaskResponse[] }) {
  const { setNodeRef, isOver } = useDroppable({
    id: `preview-${group.id}`,
    data: { kind: 'preview', groupId: group.id },
  });
  return (
    <div className="group-preview" aria-label={`Предпросмотр группы ${group.name}`}>
      <header className="group-stage-head">
        <GroupEmblem group={group} />
        <div>
          <h2>{group.name}</h2>
          <p>Отпусти над пунктиром или значком группы</p>
        </div>
      </header>
      <div ref={setNodeRef} className={cn('group-preview-drop', isOver && 'is-over')}>
        <div className="group-task-grid">
          {tasks.slice(0, 4).map((task) => (
            <div className="preview-task" key={task.id}>
              <span className="preview-ring" />
              <div>
                <strong>{task.title}</strong>
                <small>{task.estimateMinutes ? `${task.estimateMinutes} мин` : 'Без оценки'}</small>
              </div>
            </div>
          ))}
        </div>
        <p className="preview-caption">
          {tasks.length > 4
            ? `Ещё ${tasks.length - 4} задач в группе`
            : tasks.length
              ? 'Задача появится в этой группе'
              : 'Здесь есть место для задачи'}
        </p>
      </div>
    </div>
  );
}
const collision: CollisionDetection = (args) => {
  const hits = args.pointerCoordinates ? pointerWithin(args) : rectIntersection(args);
  return [...hits].sort((a, b) => Number(b.id === 'cancel') - Number(a.id === 'cancel'));
};
export function GroupsPage() {
  const {
    data: groups = [],
    isLoading: loadingGroups,
    error: groupError,
    refetch: refetchGroups,
  } = useGroups();
  const {
    data: tasks = [],
    isLoading: loadingTasks,
    error: taskError,
    refetch: refetchTasks,
  } = useTasksList();
  const update = useUpdateTask(),
    remove = useDeleteGroup();
  const reveal = useTaskUi((s) => s.reveal),
    revealIds = useTaskUi((s) => s.revealIds),
    revealedAt = useTaskUi((s) => s.revealedAt);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [opened, setOpened] = useState(false);
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(12);
  const [history, setHistory] = useState(false);
  const [page, setPage] = useState(1);
  const [pinned, setPinned] = useState<string | null>(null);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [create, setCreate] = useState(false);
  const [editor, setEditor] = useState<{ group: GroupResponse | null } | null>(null);
  const [deleting, setDeleting] = useState<GroupResponse | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [hoverId, setHoverId] = useState<string | null>(null);
  const [previewId, setPreviewId] = useState<string | null>(null);
  const selected = groups.find((g) => g.id === selectedId) || groups[0];
  const preview = groups.find((g) => g.id === previewId);
  const activeTask = tasks.find((t) => t.id === activeId);
  const stageRef = useRef<HTMLDivElement>(null);
  const frozenHeight = useRef(510);
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor)
  );
  const matching = groups.filter((g) =>
    g.name.toLocaleLowerCase('ru').includes(query.toLocaleLowerCase('ru'))
  );
  const visibleGroups = query ? matching : matching.slice(0, limit);
  const members = tasks.filter((t) => t.groupId === selected?.id);
  const items = members
    .filter((t) => (history ? !isActiveTask(t) : isActiveTask(t)))
    .sort((a, b) => {
      if (a.id === pinned) return -1;
      if (b.id === pinned) return 1;
      const ai = revealIds.indexOf(a.id),
        bi = revealIds.indexOf(b.id);
      if (Date.now() - revealedAt < 8000 && ai !== bi)
        return (ai < 0 ? 10000 : ai) - (bi < 0 ? 10000 : bi);
      return Date.parse(b.createdAt) - Date.parse(a.createdAt);
    });
  const pages = Math.max(1, Math.ceil(items.length / 12)),
    current = Math.min(page, pages);
  useEffect(() => {
    if (!activeId || !hoverId || hoverId === selected?.id) return;
    const timer = setTimeout(() => setPreviewId(hoverId), 550);
    return () => clearTimeout(timer);
  }, [activeId, hoverId, selected?.id]);
  useEffect(() => {
    if (revealedAt && Date.now() - revealedAt < 8000) {
      setPage(1);
      setHistory(false);
    }
  }, [revealedAt]);
  const dragOver = (event: DragOverEvent) => {
    const data = event.over?.data.current;
    setHoverId(data?.kind === 'tile' ? String(data.groupId) : null);
  };
  const end = async (event: DragEndEvent) => {
    const target = event.over?.data.current;
    const task = tasks.find((t) => t.id === String(event.active.id));
    setActiveId(null);
    setPreviewId(null);
    setHoverId(null);
    if (!task || !target?.groupId || target.kind === 'cancel' || target.groupId === task.groupId)
      return;
    const id = String(target.groupId);
    try {
      await update.mutateAsync({ id: task.id, groupId: id });
      setSelectedId(id);
      setOpened(true);
      setPage(1);
      setHistory(false);
      setPinned(task.id);
      reveal([task.id]);
      toast.success(`Задача перенесена: ${groups.find((g) => g.id === id)?.name}.`, {
        action: {
          label: 'Отменить',
          onClick: () => {
            update.mutate(
              { id: task.id, groupId: task.groupId ?? null },
              {
                onSuccess: () => {
                  setSelectedId(task.groupId ?? null);
                  setPinned(task.id);
                  reveal([task.id]);
                },
                onError: () => toast.error('Не удалось отменить перенос'),
              }
            );
          },
        },
      });
    } catch {
      toast.error('Не удалось перенести задачу. Она осталась в исходной группе.');
    }
  };
  const open = (group: GroupResponse) => {
    setSelectedId(group.id);
    setOpened(true);
    setHistory(false);
    setPage(1);
    setPinned(null);
  };
  return (
    <div className="munin-page">
      <header className="munin-page-head">
        <h1>Группы</h1>
        <Button onClick={() => setEditor({ group: null })} className="gap-2">
          <IconPlus />
          Группа
        </Button>
      </header>
      <div className="group-page-caption">
        <span>{groups.length} групп</span>
        <span className="task-interaction-hint">
          <span className="mouse-hint">
            ЛКМ: открыть · ПКМ: управлять · задачи можно перетаскивать
          </span>
          <span className="touch-hint">Управление в ⋯</span>
        </span>
      </div>
      {loadingGroups || loadingTasks ? (
        <div className="page-state">Загружаем группы…</div>
      ) : groupError || taskError ? (
        <div className="page-state">
          <p>Не удалось загрузить группы.</p>
          <Button
            onClick={() => {
              void refetchGroups();
              void refetchTasks();
            }}
          >
            Повторить
          </Button>
        </div>
      ) : (
        <DndContext
          accessibility={{
            screenReaderInstructions: {
              draggable:
                'Нажми пробел, чтобы взять задачу, стрелки — переместить, пробел — отпустить, Escape — отменить.',
            },
            announcements: {
              onDragStart: ({ active }) =>
                `Взята задача: ${tasks.find((t) => t.id === active.id)?.title || 'задача'}`,
              onDragOver: ({ over }) =>
                over?.data.current?.groupId
                  ? `Цель: ${groups.find((g) => g.id === over.data.current?.groupId)?.name || 'группа'}`
                  : 'Вне области переноса. Отпускание отменит перенос.',
              onDragEnd: ({ over }) =>
                over?.data.current?.groupId ? 'Задача отпущена над группой.' : 'Перенос отменён.',
              onDragCancel: () => 'Перенос отменён.',
            },
          }}
          sensors={sensors}
          collisionDetection={collision}
          onDragStart={(e) => {
            frozenHeight.current = stageRef.current?.getBoundingClientRect().height || 510;
            setActiveId(String(e.active.id));
            setPreviewId(null);
            setHoverId(null);
          }}
          onDragOver={dragOver}
          onDragEnd={(e) => void end(e)}
          onDragCancel={() => {
            setActiveId(null);
            setPreviewId(null);
            setHoverId(null);
          }}
          autoScroll={false}
        >
          <div className={cn('group-workspace', opened && 'group-workspace-open')}>
            <aside className="group-index">
              <label className="munin-search">
                <IconSearch />
                <input
                  type="search"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  aria-label="Найти группу"
                  placeholder="Найти группу"
                />
              </label>
              <div className="group-rail">
                {visibleGroups.map((group) => (
                  <GroupTile
                    key={group.id}
                    group={group}
                    count={tasks.filter((t) => t.groupId === group.id && isActiveTask(t)).length}
                    selected={group.id === selected?.id}
                    onOpen={() => open(group)}
                    onEdit={() => setEditor({ group })}
                    onDelete={() => setDeleting(group)}
                    dragging={Boolean(activeId)}
                  />
                ))}
              </div>
              <div className="group-index-caption">
                <span>
                  {visibleGroups.length} из {matching.length}
                </span>
                {visibleGroups.length < matching.length && (
                  <button className="text-button" onClick={() => setLimit(limit + 12)}>
                    Ещё группы
                  </button>
                )}
              </div>
              {!matching.length && (
                <p className="page-note">
                  {groups.length ? 'Группа не найдена' : 'Создай первую группу'}
                </p>
              )}
            </aside>
            <div
              ref={stageRef}
              className="group-stage"
              style={activeId ? { minHeight: frozenHeight.current } : undefined}
            >
              {selected ? (
                <>
                  <button className="group-back text-button" onClick={() => setOpened(false)}>
                    <IconArrowLeft />
                    Все группы
                  </button>
                  <header className="group-stage-head">
                    <GroupEmblem group={selected} />
                    <div>
                      <h2>{selected.name}</h2>
                      <p>
                        {members.filter(isActiveTask).length} активных ·{' '}
                        {members.filter((t) => !isActiveTask(t)).length} в истории
                      </p>
                    </div>
                    <Dropdown.Root>
                      <Dropdown.Trigger asChild>
                        <button
                          className="task-more"
                          aria-label={`Управлять открытой группой ${selected.name}`}
                        >
                          <IconDots />
                        </button>
                      </Dropdown.Trigger>
                      <Dropdown.Portal>
                        <Dropdown.Content className="task-menu" align="end">
                          <GroupMenu
                            group={selected}
                            kind="dropdown"
                            onEdit={() => setEditor({ group: selected })}
                            onDelete={() => setDeleting(selected)}
                          />
                        </Dropdown.Content>
                      </Dropdown.Portal>
                    </Dropdown.Root>
                  </header>
                  <div className="group-stage-tools">
                    <Button variant="outline" onClick={() => setCreate(true)} className="gap-2">
                      <IconPlus />
                      Задача
                    </Button>
                    <button
                      className="text-button"
                      onClick={() => {
                        setHistory(!history);
                        setPage(1);
                      }}
                    >
                      {history ? 'Активные' : 'Завершённые и отменённые'}
                    </button>
                  </div>
                  <div className="group-task-grid">
                    {items.slice((current - 1) * 12, current * 12).map((task) => (
                      <DraggableTask
                        key={task.id}
                        task={task}
                        onOpen={() => setTaskId(task.id)}
                        disabled={history}
                      />
                    ))}
                  </div>
                  {!items.length && (
                    <div className="empty-task-list">
                      <h2>{history ? 'История пока пуста' : 'Папка свободна'}</h2>
                      <p>
                        {history
                          ? 'Завершённые задачи появятся здесь.'
                          : 'Добавь задачу или перенеси её из другой группы.'}
                      </p>
                    </div>
                  )}
                  {pages > 1 && (
                    <nav className="munin-pager" aria-label="Страницы задач группы">
                      <span>
                        {(current - 1) * 12 + 1}–{Math.min(current * 12, items.length)} из{' '}
                        {items.length}
                      </span>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={current === 1}
                        onClick={() => {
                          setPage(current - 1);
                          setPinned(null);
                        }}
                      >
                        Назад
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={current === pages}
                        onClick={() => {
                          setPage(current + 1);
                          setPinned(null);
                        }}
                      >
                        Далее
                      </Button>
                    </nav>
                  )}
                </>
              ) : (
                <div className="empty-task-list">
                  <h2>Создай первую группу</h2>
                </div>
              )}
              {activeId && preview && (
                <Preview
                  group={preview}
                  tasks={tasks.filter((t) => t.groupId === preview.id && isActiveTask(t))}
                />
              )}
            </div>
          </div>
          <CancelDrop active={Boolean(activeId)} />
          <DragOverlay dropAnimation={null}>
            {activeTask ? <div className="group-drag-ghost">{activeTask.title}</div> : null}
          </DragOverlay>
        </DndContext>
      )}
      <GroupEditor
        open={Boolean(editor)}
        group={editor?.group || null}
        onClose={() => setEditor(null)}
        onSaved={(g) => open(g)}
      />
      <CreateTaskDialog
        open={create}
        onClose={() => setCreate(false)}
        groupId={selected?.id}
        onCreated={(task) => {
          if (task.groupId) {
            setSelectedId(task.groupId);
            setOpened(true);
            setPinned(task.id);
          }
          setHistory(false);
          setPage(1);
        }}
        onInspect={setTaskId}
      />
      <TaskDetailModal
        task={tasks.find((t) => t.id === taskId) || null}
        open={Boolean(taskId)}
        onClose={() => setTaskId(null)}
      />
      <AlertDialog open={Boolean(deleting)} onOpenChange={(v) => !v && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Удалить группу «{deleting?.name}»?</AlertDialogTitle>
            <AlertDialogDescription>Задачи останутся в списке без группы.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Отмена</AlertDialogCancel>
            <AlertDialogAction
              disabled={remove.isPending}
              onClick={(e) => {
                e.preventDefault();
                if (deleting)
                  remove.mutate(deleting.id, {
                    onSuccess: () => {
                      setDeleting(null);
                      toast.success('Группа удалена, задачи сохранены.');
                    },
                    onError: () => toast.error('Не удалось удалить группу'),
                  });
              }}
            >
              Удалить
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
