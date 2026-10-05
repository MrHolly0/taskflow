import { useEffect, useState } from 'react';
import { IconSearch, IconPlus, IconAdjustmentsHorizontal, IconX } from '@tabler/icons-react';
import { useTasksList, useGroups, useCompleteTask, useUpdateTask } from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useMinuteTick } from '@/lib/hooks/useMinuteTick';
import { endOfZonedDay } from '@/lib/utils';
import { useTaskUi, isActiveTask } from '@/lib/task-ui';
import { toast } from 'sonner';
import { Button } from '@/app/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { TaskRow } from '@/app/components/TaskRow';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { CreateTaskDialog } from '@/app/components/CreateTaskDialog';

type Context = 'now' | 'today' | 'week' | 'all';
export function AllTasksPage() {
  const [context, setContext] = useState<Context>('all');
  const [search, setSearch] = useState('');
  const [group, setGroup] = useState('all');
  const [status, setStatus] = useState('active');
  const [order, setOrder] = useState('default');
  const [filters, setFilters] = useState(false);
  const [page, setPage] = useState(1);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [selecting, setSelecting] = useState(false);
  const [selection, setSelection] = useState<string[]>([]);
  const { data: all = [], isLoading, error, refetch } = useTasksList();
  const { data: groups = [] } = useGroups();
  const complete = useCompleteTask();
  const update = useUpdateTask();
  const { timezone, isReady, error: timezoneError, refetch: refetchTimezone } = useUserTimezone();
  const revealIds = useTaskUi((s) => s.revealIds);
  const revealedAt = useTaskUi((s) => s.revealedAt);
  useMinuteTick();
  useEffect(() => {
    setPage(1);
  }, [context, search, group, status, order]);
  useEffect(() => {
    if (revealedAt && Date.now() - revealedAt < 8000) {
      setContext('all');
      setSearch('');
      setGroup('all');
      setStatus('active');
      setOrder('default');
      setPage(1);
    }
  }, [revealedAt]);
  const filtered = (() => {
    const endToday = endOfZonedDay(0, timezone),
      endWeek = endOfZonedDay(6, timezone);
    return all
      .filter((task) => {
        if (search && !task.title.toLocaleLowerCase('ru').includes(search.toLocaleLowerCase('ru')))
          return false;
        if (group !== 'all' && (group === 'none' ? Boolean(task.groupId) : task.groupId !== group))
          return false;
        if (status === 'active' && !isActiveTask(task)) return false;
        if (status === 'history' && isActiveTask(task)) return false;
        if (!['active', 'history', 'all'].includes(status) && task.status !== status) return false;
        const target = task.plannedDate || task.deadline;
        if (context === 'now')
          return (
            isActiveTask(task) &&
            (task.priority === 'URGENT' ||
              task.status === 'IN_PROGRESS' ||
              Boolean(task.deadline && new Date(task.deadline) < new Date()))
          );
        if (context === 'today')
          return isActiveTask(task) && (!target || new Date(target) <= endToday);
        if (context === 'week')
          return isActiveTask(task) && (!target || new Date(target) <= endWeek);
        return true;
      })
      .sort((a, b) => {
        if (order === 'default' && Date.now() - revealedAt < 8000) {
          const ai = revealIds.indexOf(a.id),
            bi = revealIds.indexOf(b.id);
          if (ai !== bi) return (ai < 0 ? 1000 : ai) - (bi < 0 ? 1000 : bi);
        }
        if (order === 'deadline')
          return (
            (a.deadline ? Date.parse(a.deadline) : Infinity) -
            (b.deadline ? Date.parse(b.deadline) : Infinity)
          );
        if (order === 'priority') {
          const p = ['URGENT', 'HIGH', 'MEDIUM', 'LOW'];
          return p.indexOf(a.priority) - p.indexOf(b.priority);
        }
        if (order === 'status')
          return Number(b.status === 'IN_PROGRESS') - Number(a.status === 'IN_PROGRESS');
        return Date.parse(b.createdAt) - Date.parse(a.createdAt);
      });
  })();
  const pages = Math.max(1, Math.ceil(filtered.length / 20)),
    current = Math.min(page, pages),
    visible = filtered.slice((current - 1) * 20, current * 20);
  const reset = () => {
    setContext('all');
    setSearch('');
    setGroup('all');
    setStatus('active');
    setOrder('default');
    setPage(1);
  };
  const bulk = async (done: boolean) => {
    const results = await Promise.allSettled(
      selection.map((id) =>
        done ? complete.mutateAsync(id) : update.mutateAsync({ id, status: 'CANCELLED' })
      )
    );
    const failed = results.filter((r) => r.status === 'rejected').length;
    failed
      ? toast.error(`Не изменилось ${failed} задач. Попробуй ещё раз.`)
      : toast.success(done ? 'Выбранные дела завершены.' : 'Выбранные дела отменены.');
    setSelection([]);
    setSelecting(false);
  };
  return (
    <div className="munin-page">
      <header className="munin-page-head">
        <h1>Все задачи</h1>
        <Button onClick={() => setCreateOpen(true)} className="gap-2">
          <IconPlus />
          Добавить
        </Button>
      </header>
      <div className="task-workbar">
        <div className="task-contexts" role="group" aria-label="Период задач">
          {(
            [
              ['now', 'Сейчас'],
              ['today', 'Сегодня'],
              ['week', 'Неделя'],
              ['all', 'Все'],
            ] as const
          ).map(([v, l]) => (
            <button key={v} aria-pressed={context === v} onClick={() => setContext(v)}>
              {l}
            </button>
          ))}
        </div>
        <label className="munin-search">
          <IconSearch />
          <input
            type="search"
            aria-label="Найти задачу"
            placeholder="Найти задачу"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </label>
        <Button
          variant="outline"
          size="icon"
          aria-label="Фильтры задач"
          aria-expanded={filters}
          onClick={() => setFilters(!filters)}
        >
          <IconAdjustmentsHorizontal />
        </Button>
      </div>
      {filters && (
        <div className="task-list-filters">
          <label>
            Группа
            <Select value={group} onValueChange={setGroup}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">Все группы</SelectItem>
                <SelectItem value="none">Без группы</SelectItem>
                {groups.map((g) => (
                  <SelectItem value={g.id} key={g.id}>
                    {g.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <label>
            Статус
            <Select value={status} onValueChange={setStatus}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {[
                  ['active', 'Активные'],
                  ['all', 'Все статусы'],
                  ['TODO', 'В планах'],
                  ['IN_PROGRESS', 'В работе'],
                  ['history', 'Завершённые и отменённые'],
                ].map(([v, l]) => (
                  <SelectItem value={v} key={v}>
                    {l}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <label>
            Порядок
            <Select value={order} onValueChange={setOrder}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {[
                  ['default', 'Новые сначала'],
                  ['deadline', 'По сроку'],
                  ['priority', 'По приоритету'],
                  ['status', 'В работе сначала'],
                ].map(([v, l]) => (
                  <SelectItem value={v} key={v}>
                    {l}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>
          <button className="text-button" onClick={reset}>
            <IconX />
            Сбросить
          </button>
        </div>
      )}
      <div className="task-list-caption">
        <span>
          {all.filter(isActiveTask).length} активных · {all.filter((t) => !isActiveTask(t)).length}{' '}
          в истории
        </span>
        <button
          className="text-button"
          onClick={() => {
            setSelecting(!selecting);
            setSelection([]);
          }}
        >
          {selecting ? 'Отменить выбор' : 'Выбрать несколько'}
        </button>
      </div>
      {selecting && (
        <div className="task-selection-bar">
          <span>Выбрано {selection.length}</span>
          <Button
            size="sm"
            disabled={!selection.length || complete.isPending || update.isPending}
            onClick={() => void bulk(true)}
          >
            Завершить
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={!selection.length || complete.isPending || update.isPending}
            onClick={() => void bulk(false)}
          >
            Отменить задачи
          </Button>
        </div>
      )}
      {error || timezoneError ? (
        <div className="page-state">
          <p>Не удалось загрузить задачи.</p>
          <Button
            variant="outline"
            onClick={() => {
              void refetch();
              void refetchTimezone();
            }}
          >
            Повторить
          </Button>
        </div>
      ) : isLoading || !isReady ? (
        <div className="page-state">Загружаем задачи…</div>
      ) : (
        <>
          <div>
            {visible.map((task) => (
              <div key={task.id} className={selecting ? 'task-select-row' : ''}>
                {selecting && (
                  <input
                    type="checkbox"
                    aria-label={`Выбрать: ${task.title}`}
                    checked={selection.includes(task.id)}
                    onChange={(e) =>
                      setSelection(
                        e.target.checked
                          ? [...selection, task.id]
                          : selection.filter((id) => id !== task.id)
                      )
                    }
                  />
                )}
                <TaskRow task={task} onOpen={() => setSelectedId(task.id)} />
              </div>
            ))}
          </div>
          {!visible.length && (
            <div className="empty-task-list">
              <h2>{all.length ? 'По этим условиям ничего нет' : 'Здесь появятся твои задачи'}</h2>
              <button
                className="text-button"
                onClick={all.length ? reset : () => setCreateOpen(true)}
              >
                {all.length ? 'Сбросить фильтры' : 'Добавить первое дело'}
              </button>
            </div>
          )}
          {pages > 1 && (
            <nav className="munin-pager" aria-label="Страницы задач">
              <span>
                {(current - 1) * 20 + 1}–{Math.min(current * 20, filtered.length)} из{' '}
                {filtered.length}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={current === 1}
                onClick={() => setPage(current - 1)}
              >
                Назад
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={current === pages}
                onClick={() => setPage(current + 1)}
              >
                Далее
              </Button>
            </nav>
          )}
          <p className="task-interaction-hint">
            <span className="mouse-hint">ПКМ по задаче или ⋯ для быстрых действий</span>
            <span className="touch-hint">Удерживай задачу или нажми ⋯</span>
          </p>
        </>
      )}
      <CreateTaskDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        groupId={group !== 'all' && group !== 'none' ? group : undefined}
      />
      <TaskDetailModal
        task={all.find((t) => t.id === selectedId) || null}
        open={Boolean(selectedId)}
        onClose={() => setSelectedId(null)}
      />
    </div>
  );
}
