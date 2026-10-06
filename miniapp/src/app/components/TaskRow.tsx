import { useState, type ReactElement, type ReactNode } from 'react';
import * as ContextMenu from '@radix-ui/react-context-menu';
import * as Dropdown from '@radix-ui/react-dropdown-menu';
import {
  IconCheck,
  IconClock,
  IconDots,
  IconPencil,
  IconPlayerPlay,
  IconArrowBack,
  IconFolder,
  IconX,
  IconBell,
  IconRepeat,
} from '@tabler/icons-react';
import { toast } from 'sonner';
import { addDays } from 'date-fns';
import { fromZonedTime, toZonedTime } from 'date-fns-tz';
import {
  useCompleteTask,
  useUpdateTask,
  useGroups,
  type TaskResponse,
  type UpdateTaskRequest,
} from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { cn, formatDeadline, formatReminderTime, formatPlannedDate } from '@/lib/utils';
import { useTaskReveal, isActiveTask } from '@/lib/task-ui';
import { getGroupIcon } from '@/lib/group-icons';
import { Dialog, DialogContent, DialogTitle, DialogDescription } from '@/app/components/ui/dialog';

export function useTaskActions(task: TaskResponse) {
  const complete = useCompleteTask();
  const update = useUpdateTask();
  const { timezone } = useUserTimezone();
  const patch = async (changes: Omit<UpdateTaskRequest, 'id'>, label: string) => {
    try {
      await update.mutateAsync({ id: task.id, ...changes });
      const previous: UpdateTaskRequest = { id: task.id };
      if ('status' in changes) previous.status = task.status;
      if ('groupId' in changes) previous.groupId = task.groupId ?? null;
      if ('plannedDate' in changes) previous.plannedDate = task.plannedDate ?? null;
      toast.success(label, {
        action:
          changes.status === 'CANCELLED'
            ? undefined
            : {
                label: 'Отменить',
                onClick: () => {
                  update.mutate(previous, {
                    onError: () => toast.error('Не удалось вернуть изменение'),
                  });
                },
              },
      });
    } catch {
      toast.error('Не удалось изменить задачу. Попробуй ещё раз.');
    }
  };
  return {
    busy: complete.isPending || update.isPending,
    patch,
    done: async () => {
      try {
        await complete.mutateAsync(task.id);
        toast.success('Готово. Ещё одно дело позади.');
      } catch {
        toast.error('Не удалось завершить задачу');
      }
    },
    snooze: () => {
      const tomorrow = addDays(toZonedTime(new Date(), timezone), 1);
      tomorrow.setHours(9, 0, 0, 0);
      return patch(
        { plannedDate: fromZonedTime(tomorrow, timezone).toISOString() },
        'Дело перенесено на завтра.'
      );
    },
  };
}

export function TaskActionMenu({
  task,
  onOpen,
  children,
  moreButtonClassName = 'task-more',
  contextDisabled = false,
}: {
  task: TaskResponse;
  onOpen: () => void;
  children: (menu: {
    moreButton: ReactNode;
    menuOpen: boolean;
    actions: ReturnType<typeof useTaskActions>;
  }) => ReactElement;
  moreButtonClassName?: string;
  contextDisabled?: boolean;
}) {
  const actions = useTaskActions(task);
  const { data: groups = [] } = useGroups();
  const [contextOpen, setContextOpen] = useState(false);
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);
  const closed = !isActiveTask(task);
  function items(kind: 'context' | 'dropdown') {
    const Menu = kind === 'context' ? ContextMenu : Dropdown;
    return (
      <>
        <Menu.Label className="task-menu-label">
          {task.title}
          <small>Быстрые действия</small>
        </Menu.Label>
        <Menu.Item className="task-menu-item" onSelect={onOpen}>
          <IconPencil />
          Открыть детали
        </Menu.Item>
        <Menu.Item
          className="task-menu-item"
          disabled={actions.busy}
          onSelect={() =>
            closed
              ? void actions.patch({ status: 'TODO' }, 'Задача возвращена в список.')
              : void actions.done()
          }
        >
          <IconCheck />
          {closed ? 'Вернуть в список' : 'Завершить'}
        </Menu.Item>
        {!closed && (
          <>
            <Menu.Item
              className="task-menu-item"
              disabled={actions.busy}
              onSelect={() =>
                void actions.patch(
                  { status: task.status === 'IN_PROGRESS' ? 'TODO' : 'IN_PROGRESS' },
                  task.status === 'IN_PROGRESS' ? 'Задача возвращена в список.' : 'Задача в работе.'
                )
              }
            >
              <IconPlayerPlay />
              {task.status === 'IN_PROGRESS' ? 'Вернуть в список' : 'Взять в работу'}
            </Menu.Item>
            <Menu.Item
              className="task-menu-item"
              disabled={actions.busy}
              onSelect={() => void actions.snooze()}
            >
              <IconClock />
              Отложить на завтра
            </Menu.Item>
          </>
        )}
        <Menu.Item className="task-menu-item task-move-mobile" onSelect={() => setMoveOpen(true)}>
          <IconFolder />
          Переместить в группу
        </Menu.Item>
        <Menu.Sub>
          <Menu.SubTrigger className="task-menu-item task-move-desktop">
            <IconFolder />
            Переместить в группу<span className="ml-auto">›</span>
          </Menu.SubTrigger>
          <Menu.Portal>
            <Menu.SubContent className="task-menu max-h-64 overflow-y-auto" sideOffset={4}>
              {[{ id: '', name: 'Без группы' }, ...groups].map((g) => (
                <Menu.Item
                  key={g.id}
                  className="task-menu-item"
                  disabled={actions.busy || (task.groupId ?? '') === g.id}
                  onSelect={() =>
                    void actions.patch({ groupId: g.id || null }, `Группа: ${g.name}.`)
                  }
                >
                  {g.name}
                </Menu.Item>
              ))}
            </Menu.SubContent>
          </Menu.Portal>
        </Menu.Sub>
        {!closed && (
          <Menu.Item
            className="task-menu-item text-destructive"
            disabled={actions.busy}
            onSelect={() => void actions.patch({ status: 'CANCELLED' }, 'Задача отменена.')}
          >
            <IconX />
            Отменить задачу
          </Menu.Item>
        )}
      </>
    );
  }
  const moreButton = (
    <Dropdown.Root onOpenChange={setDropdownOpen}>
      <Dropdown.Trigger asChild>
        <button
          type="button"
          className={moreButtonClassName}
          aria-label={`Действия: ${task.title}`}
          onPointerDown={(event) => event.stopPropagation()}
          onMouseDown={(event) => event.stopPropagation()}
          onTouchStart={(event) => event.stopPropagation()}
          onClick={(event) => event.stopPropagation()}
          onContextMenu={(event) => event.stopPropagation()}
          onKeyDown={(event) => event.stopPropagation()}
        >
          <IconDots />
        </button>
      </Dropdown.Trigger>
      <Dropdown.Portal>
        <Dropdown.Content
          className="task-menu"
          align="end"
          sideOffset={4}
          onClick={(event) => event.stopPropagation()}
          onPointerDown={(event) => event.stopPropagation()}
          onMouseDown={(event) => event.stopPropagation()}
          onTouchStart={(event) => event.stopPropagation()}
          onKeyDown={(event) => event.stopPropagation()}
        >
          {items('dropdown')}
        </Dropdown.Content>
      </Dropdown.Portal>
    </Dropdown.Root>
  );
  return (
    <ContextMenu.Root onOpenChange={setContextOpen}>
      <ContextMenu.Trigger asChild disabled={contextDisabled}>
        {children({ moreButton, menuOpen: contextOpen || dropdownOpen, actions })}
      </ContextMenu.Trigger>
      <ContextMenu.Portal>
        <ContextMenu.Content className="task-menu">{items('context')}</ContextMenu.Content>
      </ContextMenu.Portal>
      <Dialog open={moveOpen} onOpenChange={setMoveOpen}>
        <DialogContent className="task-group-picker">
          <DialogTitle>Переместить в группу</DialogTitle>
          <DialogDescription>{task.title}</DialogDescription>
          <div className="task-group-options">
            {[{ id: '', name: 'Без группы', icon: 'folder' }, ...groups].map(group => {
              const Icon = getGroupIcon(group.icon || 'folder');
              const selected = (task.groupId ?? '') === group.id;
              return (
                <button key={group.id} disabled={actions.busy || selected}
                  className="task-group-option" onClick={() => {
                    void actions.patch({ groupId: group.id || null }, `Группа: ${group.name}.`);
                    setMoveOpen(false);
                  }}>
                  <Icon />
                  <span>{group.name}</span>
                  {selected && <IconCheck aria-label="Текущая группа" />}
                </button>
              );
            })}
          </div>
        </DialogContent>
      </Dialog>
    </ContextMenu.Root>
  );
}

export function TaskRow({
  task,
  onOpen,
  variant = 'list',
  primary = false,
  dragHandle,
}: {
  task: TaskResponse;
  onOpen: () => void;
  variant?: 'focus' | 'list' | 'folder';
  primary?: boolean;
  dragHandle?: ReactNode;
}) {
  const { timezone } = useUserTimezone();
  const ref = useTaskReveal(task.id);
  const closed = !isActiveTask(task);
  return (
    <TaskActionMenu task={task} onOpen={onOpen}>
      {({ moreButton, menuOpen, actions }) => (
        <article
          ref={ref}
          data-task-id={task.id}
          className={cn(
            'munin-task',
            `munin-task-${variant}`,
            closed && 'task-closed',
            menuOpen && 'task-menu-lifted'
          )}
        >
          <button
            className="task-complete"
            role="checkbox"
            aria-checked={task.status === 'DONE'}
            aria-label={`${closed ? 'Вернуть' : 'Завершить'}: ${task.title}`}
            disabled={actions.busy}
            onClick={() =>
              closed
                ? void actions.patch({ status: 'TODO' }, 'Задача возвращена.')
                : void actions.done()
            }
          >
            <span>{task.status === 'DONE' && <IconCheck />}</span>
          </button>
          <div className="munin-task-body">
            <button className="munin-task-title" onClick={onOpen}>
              {task.title}
            </button>
            <div className="munin-task-meta">
              <span>
                <IconClock />
                {task.deadline
                  ? `Срок: ${formatDeadline(task.deadline, timezone, task.status)}`
                  : task.plannedDate
                    ? `День исполнения: ${formatPlannedDate(task.plannedDate, timezone)}`
                    : 'Без срока'}
              </span>
              {task.estimateMinutes != null && <span>{task.estimateMinutes} мин</span>}
              {variant !== 'folder' && task.groupName && <span>{task.groupName}</span>}
              {task.status === 'IN_PROGRESS' && <span className="task-in-progress">В работе</span>}
              {task.status === 'CANCELLED' && <span>Отменено</span>}
              {task.priority === 'URGENT' && <span className="text-destructive">Срочно</span>}
              {task.recurrence && <IconRepeat aria-label="Повторяется" />}
              {task.reminders?.[0] && (
                <span>
                  <IconBell />
                  {formatReminderTime(task.reminders[0].fireAt, timezone)}
                </span>
              )}
            </div>
            {variant === 'focus' && !closed && (
              <div className="munin-task-controls">
                <button
                  className={cn(
                    'task-action',
                    (primary || task.status === 'IN_PROGRESS') && 'task-action-primary'
                  )}
                  disabled={actions.busy}
                  onClick={() =>
                    task.status === 'IN_PROGRESS'
                      ? void actions.done()
                      : void actions.patch({ status: 'IN_PROGRESS' }, 'Задача в работе.')
                  }
                >
                  {task.status === 'IN_PROGRESS' ? 'Завершить' : 'Начать'}
                </button>
                {task.status === 'IN_PROGRESS' && (
                  <button
                    className="task-action task-action-quiet"
                    disabled={actions.busy}
                    onClick={() =>
                      void actions.patch({ status: 'TODO' }, 'Задача возвращена в список.')
                    }
                  >
                    <IconArrowBack className="sr-only" />
                    Вернуть в список
                  </button>
                )}
                <button
                  className="task-action task-action-quiet"
                  disabled={actions.busy}
                  onClick={() => void actions.snooze()}
                >
                  Отложить
                </button>
              </div>
            )}
            {dragHandle}
          </div>
          {moreButton}
        </article>
      )}
    </TaskActionMenu>
  );
}
