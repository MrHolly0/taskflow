import { fromZonedTime } from 'date-fns-tz';
import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { IconArrowRight, IconLoader2 } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { useGroups, useTasksList, useTaskPage, useTaskOverview, useTaskStats, useUpdateTask } from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useSettings } from '@/lib/hooks/useSettings';
import { useStore } from '@/lib/store';
import { useTaskUi } from '@/lib/task-ui';
import { zonedDayKey } from '@/lib/utils';
import '@/styles/stats.css';

function readGoal(key: string): number {
  try {
    const saved = Number(localStorage.getItem(key));
    return Number.isInteger(saved) && saved >= 1 && saved <= 1000 ? saved : 5;
  } catch {
    return 5;
  }
}

function deeds(count: number): string {
  const tail = count % 10,
    teen = count % 100;
  if (tail === 1 && teen !== 11) return 'дело';
  if (tail >= 2 && tail <= 4 && (teen < 12 || teen > 14)) return 'дела';
  return 'дел';
}

// Ключи календарных дней сдвигаем в UTC, чтобы переход летнего времени не менял окно.
function recentDays(today: string): string[] {
  const date = new Date(`${today}T12:00:00Z`);
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(date);
    day.setUTCDate(day.getUTCDate() - 6 + index);
    return day.toISOString().slice(0, 10);
  });
}

function dayLabel(key: string, weekday = false): string {
  return new Date(`${key}T12:00:00Z`).toLocaleDateString(
    'ru-RU',
    weekday
      ? { weekday: 'short', timeZone: 'UTC' }
      : { day: 'numeric', month: 'short', timeZone: 'UTC' }
  );
}

export function StatsPage() {
  const statsQuery = useTaskStats();
  const { data: groups = [] } = useGroups();
  const settingsQuery = useSettings();
  const { timezone, isReady: timezoneReady } = useUserTimezone();
  const updateTask = useUpdateTask();
  const navigate = useNavigate();
  const reveal = useTaskUi((state) => state.reveal);
  const openCapture = useTaskUi((state) => state.openCapture);
  const userId = useStore((state) => state.user?.id);
  const goalKey = `munin-weekly-goal:${userId ?? 'local'}`;
  const [goal, setGoal] = useState(() => readGoal(goalKey));
  const [goalDraft, setGoalDraft] = useState(String(goal));
  const [goalEditing, setGoalEditing] = useState(false);
  const [goalError, setGoalError] = useState('');
  const [panel, setPanel] = useState<'results' | 'directions'>('results');
  const [selectedDay, setSelectedDay] = useState<string | null>(null);
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [receiptLimit, setReceiptLimit] = useState(12);
  const [openedTaskId, setOpenedTaskId] = useState<string | null>(null);
  const [startError, setStartError] = useState('');
  useEffect(() => {
    const value = readGoal(goalKey);
    setGoal(value);
    setGoalDraft(String(value));
  }, [goalKey]);

  const today = zonedDayKey(new Date(), timezone);
  const keys = timezoneReady ? recentDays(today) : [];
  const since = fromZonedTime(`${selectedDay ?? keys[0] ?? today}T00:00:00`, timezone).toISOString();
  const untilDay = selectedDay ? new Date(`${selectedDay}T12:00:00Z`) : null;
  if (untilDay) untilDay.setUTCDate(untilDay.getUTCDate() + 1);
  const until = untilDay ? fromZonedTime(`${untilDay.toISOString().slice(0, 10)}T00:00:00`, timezone).toISOString() : undefined;
  const tasksQuery = useTasksList({ status: 'DONE', order: 'completed', size: 24, completedSince: since,
    completedUntil: until, group: selectedGroup === null ? 'all' : selectedGroup || 'none' }, timezoneReady);
  const nextQuery = useTaskPage({ status: 'active', order: 'recommendation', size: 1 });
  const overview = useTaskOverview();
  const statsTasks = statsQuery.data ?? [],
    tasks = tasksQuery.data ?? [];
  const windowKeys = new Set(keys);
  const inWindow = (value?: string | null) =>
    Boolean(value && windowKeys.has(zonedDayKey(value, timezone)));
  const days = keys.map((key) => ({
    key,
    created: statsTasks.filter(
      (task) => task.createdAt && zonedDayKey(task.createdAt, timezone) === key
    ).length,
    done: statsTasks.filter(
      (task) => task.completedAt && zonedDayKey(task.completedAt, timezone) === key
    ).length,
  }));
  const done = days.reduce((sum, day) => sum + day.done, 0);
  const created = days.reduce((sum, day) => sum + day.created, 0);
  const activeDays = days.filter((day) => day.created || day.done).length;
  const completed = tasks
    .filter((task) => task.status === 'DONE' && inWindow(task.completedAt))
    .sort((a, b) => (b.completedAt ?? '').localeCompare(a.completedAt ?? ''));
  const receipts = completed.filter(
    (task) =>
      (!selectedDay || zonedDayKey(task.completedAt!, timezone) === selectedDay) &&
      (selectedGroup === null || (task.groupId ?? '') === selectedGroup)
  );
  const progressGroups = (overview.data?.groups ?? []).filter(group => group.weeklyDone > 0).map(group => ({
    id: group.groupId, count: group.weeklyDone,
    name: groups.find(item => item.id === group.groupId)?.name ?? 'Без группы',
  })).sort((a, b) => b.count - a.count);
  const next = nextQuery.data?.content[0];
  const remaining = Math.max(0, goal - done);
  const heading =
    remaining === 0
      ? 'Твой ориентир на неделю закрыт.'
      : done === 0
        ? 'Начни с одного дела.'
        : `Ещё ${remaining === 1 ? 'одно дело' : `${remaining} ${deeds(remaining)}`} до цели.`;
  const openedTask = tasks.find((task) => task.id === openedTaskId) ?? null;
  const clearFilters = () => {
    setSelectedDay(null);
    setSelectedGroup(null);
    setReceiptLimit(12);
  };

  const saveGoal = (event: FormEvent) => {
    event.preventDefault();
    const value = Number(goalDraft);
    if (!Number.isInteger(value) || value < 1 || value > 1000) {
      setGoalError('Выбери от 1 до 1000 дел.');
      return;
    }
    try {
      localStorage.setItem(goalKey, String(value));
      setGoalError('');
    } catch {
      setGoalError('Браузер не сохранил цель. Она действует до закрытия страницы.');
    }
    setGoal(value);
    setGoalEditing(false);
  };
  const startNext = async () => {
    if (!next) return;
    setStartError('');
    try {
      if (next.status !== 'IN_PROGRESS')
        await updateTask.mutateAsync({ id: next.id, status: 'IN_PROGRESS' });
      reveal([next.id]);
      navigate('/');
    } catch {
      setStartError('Не получилось взять дело в работу. Попробуй ещё раз.');
    }
  };

  return (
    <div className="munin-stats">
      <header className="stats-heading">
        <h1>Статистика</h1>
        <p>
          {keys.length
            ? `${dayLabel(keys[0])} — ${dayLabel(keys[6])} · последние 7 дней`
            : 'Твоя неделя'}
        </p>
      </header>
      {statsQuery.isError || settingsQuery.isError ? (
        <div className="stats-empty" role="alert">
          <h2>Не удалось загрузить статистику</h2>
          <p>Попробуй обновить данные.</p>
          <Button
            variant="outline"
            onClick={() => {
              void statsQuery.refetch();
              void settingsQuery.refetch();
            }}
          >
            Повторить
          </Button>
        </div>
      ) : !timezoneReady || statsQuery.isPending ? (
        <p className="stats-loading" role="status">
          <IconLoader2 className="animate-spin" size={18} /> Загружаем результаты…
        </p>
      ) : (
        <div className="stats-progress-layout" data-panel={panel}>
          <div className="stats-main">
            <section className="stats-week-intent" aria-labelledby="stats-goal-heading">
              <p className="stats-eyebrow">Ориентир недели</p>
              <h2 id="stats-goal-heading">{heading}</h2>
              <div
                className="stats-intent-track"
                role="progressbar"
                aria-label="Ориентир недели"
                aria-valuemin={0}
                aria-valuemax={goal}
                aria-valuenow={Math.min(done, goal)}
                aria-valuetext={`Завершено ${done} из ${goal}`}
              >
                <div
                  className="stats-intent-fill"
                  style={{ transform: `scaleX(${Math.min(1, done / goal)})` }}
                />
                <div
                  className="stats-intent-ticks"
                  style={{ gridTemplateColumns: `repeat(${Math.min(10, goal)}, 1fr)` }}
                >
                  {Array.from({ length: Math.min(10, goal) }, (_, index) => (
                    <i key={index} />
                  ))}
                </div>
              </div>
              <div className="stats-intent-caption">
                <span>
                  {done} из {goal} дел завершено
                </span>
                <button
                  onClick={() => {
                    setGoalDraft(String(goal));
                    setGoalEditing(!goalEditing);
                    setGoalError('');
                  }}
                  aria-expanded={goalEditing}
                >
                  Изменить цель
                </button>
              </div>
              {goalEditing && (
                <form className="stats-goal-editor" onSubmit={saveGoal}>
                  <label htmlFor="weekly-goal">Сколько дел хочешь завершить за 7 дней?</label>
                  <div>
                    <Input
                      id="weekly-goal"
                      type="number"
                      min={1}
                      max={1000}
                      required
                      value={goalDraft}
                      onChange={(event) => setGoalDraft(event.target.value)}
                      autoFocus
                    />
                    <Button type="submit">Сохранить</Button>
                    <Button type="button" variant="outline" onClick={() => setGoalEditing(false)}>
                      Отмена
                    </Button>
                  </div>
                  <p>Цель хранится в этом браузере.</p>
                </form>
              )}
              {goalError && (
                <p className="stats-error" role="status">
                  {goalError}
                </p>
              )}
            </section>
            <div className="stats-mobile-tabs" role="group" aria-label="Раздел статистики">
              <button aria-pressed={panel === 'results'} onClick={() => setPanel('results')}>
                Сделанные дела
              </button>
              <button aria-pressed={panel === 'directions'} onClick={() => setPanel('directions')}>
                Направления
              </button>
            </div>
            <section className="stats-results" aria-label="Сделанные дела">
              <div className="stats-week-rhythm" aria-label="Завершения по дням">
                {days.map((day) => (
                  <button
                    key={day.key}
                    className={`stats-rhythm-day ${day.done ? 'has-done' : ''}`}
                    aria-pressed={selectedDay === day.key}
                    aria-label={`${dayLabel(day.key)}: завершено ${day.done}`}
                    onClick={() => {
                      setSelectedDay(selectedDay === day.key ? null : day.key);
                      setReceiptLimit(12);
                    }}
                  >
                    <span>{dayLabel(day.key, true)}</span>
                    <span className="stats-rhythm-stamp">{day.done || '·'}</span>
                    <span>{Number(day.key.slice(-2))}</span>
                  </button>
                ))}
              </div>
              <div className="stats-receipt-heading">
                <h2>
                  {selectedGroup !== null
                    ? `${progressGroups.find((group) => group.id === selectedGroup)?.name ?? 'Выбранная группа'}${selectedDay ? ` · ${dayLabel(selectedDay)}` : ''}`
                    : selectedDay
                      ? `Сделано ${dayLabel(selectedDay)}`
                      : 'Что уже позади'}
                </h2>
                <button onClick={clearFilters}>Вся неделя</button>
              </div>
              {tasksQuery.isPending ? (
                <p className="stats-note" role="status">
                  Загружаем сделанные дела…
                </p>
              ) : tasksQuery.isError ? (
                <div className="stats-empty" role="alert">
                  <p>Итоги загружены, но список дел пока недоступен.</p>
                  <Button
                    variant="outline"
                    onClick={() => {
                      void tasksQuery.refetch();
                    }}
                  >
                    Повторить
                  </Button>
                </div>
              ) : (
                <>
                  {!selectedDay && selectedGroup === null && done > tasksQuery.total && (
                    <p className="stats-note">
                      В итогах учтены и завершения, убранные из списка. Ниже — {completed.length} из{' '}
                      {done} доступных дел.
                    </p>
                  )}
                  {receipts.length ? (
                    <>
                      {receipts.slice(0, receiptLimit).map((task) => (
                        <article className="stats-progress-receipt" key={task.id}>
                          <span className="stats-receipt-mark" aria-hidden="true">
                            ГОТОВО
                          </span>
                          <button onClick={() => setOpenedTaskId(task.id)}>
                            {task.title}
                            <small>{task.groupName || 'Без группы'}</small>
                          </button>
                          <time dateTime={task.completedAt}>
                            {dayLabel(zonedDayKey(task.completedAt!, timezone))}
                          </time>
                        </article>
                      ))}
                      {(receipts.length > receiptLimit || tasksQuery.hasNextPage) && (
                        <Button
                          className="stats-show-more"
                          variant="outline"
                          disabled={tasksQuery.isFetchingNextPage}
                          onClick={() => {
                            if (receipts.length <= receiptLimit && tasksQuery.hasNextPage) void tasksQuery.fetchNextPage();
                            setReceiptLimit(receiptLimit + 12);
                          }}
                        >
                          Ещё сделанные дела · {Math.max(0, tasksQuery.total - receiptLimit)}
                        </Button>
                      )}
                    </>
                  ) : (
                    <div className="stats-empty">
                      <h3>
                        {done && !selectedDay && selectedGroup === null
                          ? 'Результаты сохранены в итогах'
                          : selectedDay || selectedGroup !== null
                            ? 'В этом разделе без завершений'
                            : 'Здесь будут твои результаты'}
                      </h3>
                      <p>
                        {done && !selectedDay && selectedGroup === null
                          ? 'Завершённые дела уже убраны из списка.'
                          : selectedDay || selectedGroup !== null
                            ? 'Открой всю неделю, чтобы увидеть другие дни.'
                            : 'Каждое завершённое дело остаётся в истории недели.'}
                      </p>
                    </div>
                  )}
                </>
              )}
              <section className="stats-audit">
                <button aria-expanded={detailsOpen} onClick={() => setDetailsOpen(!detailsOpen)}>
                  {detailsOpen ? 'Скрыть числа и даты' : 'Показать числа и даты'}
                </button>
                {detailsOpen && (
                  <>
                    <p className="stats-note">
                      За те же 7 дней: добавлено {created}, завершено {done}, активных дней{' '}
                      {activeDays}. Активность включает создание и завершение.
                    </p>
                    <table>
                      <thead>
                        <tr>
                          <th>День</th>
                          <th>Добавлено</th>
                          <th>Готово</th>
                        </tr>
                      </thead>
                      <tbody>
                        {days.map((day) => (
                          <tr key={day.key}>
                            <td>{dayLabel(day.key)}</td>
                            <td>{day.created}</td>
                            <td>{day.done}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </>
                )}
              </section>
            </section>
          </div>
          <aside className="stats-progress-side">
            <h2>Что продвинулось</h2>
            {tasksQuery.isPending ? (
              <p className="stats-note">Загружаем направления…</p>
            ) : tasksQuery.isError || overview.isError ? (
              <div className="stats-note">
                <p>Не удалось загрузить направления.</p>
                <Button variant="outline" onClick={() => { void overview.refetch(); void tasksQuery.refetch(); }}>Повторить</Button>
              </div>
            ) : (
              <>

                {progressGroups.length ? (
                  progressGroups.map((group) => (
                    <button
                      key={group.id}
                      className="stats-group-progress"
                      onClick={() => {
                        setSelectedGroup(group.id);
                        setSelectedDay(null);
                        setReceiptLimit(12);
                        setPanel('results');
                      }}
                    >
                      <strong>{group.name}</strong>
                      <span>{group.count} готово</span>
                      <p>За последние семь дней</p>
                    </button>
                  ))
                ) : (
                  <p className="stats-note">Завершения покажут, какие направления ты продвинул.</p>
                )}
              </>
            )}
            {!tasksQuery.isPending && !tasksQuery.isError && (
              <section className="stats-next-step">
                {nextQuery.isPending ? <p role="status">Подбираем следующее дело…</p>
                  : nextQuery.isError ? <div role="alert"><p>Не удалось загрузить следующее дело.</p>
                    <Button variant="outline" onClick={() => void nextQuery.refetch()}>Повторить</Button></div>
                  : next ? (
                  <>
                    <p className="stats-eyebrow">Можно продолжить с этого</p>
                    <h3>{next.title}</h3>
                    <p>
                      {next.groupName || 'Без группы'}
                      {next.estimateMinutes ? ` · около ${next.estimateMinutes} мин` : ''}
                    </p>
                    <Button
                      disabled={updateTask.isPending}
                      onClick={() => {
                        void startNext();
                      }}
                    >
                      {updateTask.isPending
                        ? 'Сохраняем…'
                        : next.status === 'IN_PROGRESS'
                          ? 'Продолжить'
                          : 'Взять в работу'}
                      <IconArrowRight size={16} />
                    </Button>
                    {startError && (
                      <p className="stats-error" role="alert">
                        {startError}
                      </p>
                    )}
                  </>
                ) : (
                  <>
                    <h3>Активных дел не осталось</h3>
                    <p>Можно выдохнуть или добавить новый план.</p>
                    <Button variant="outline" onClick={openCapture}>
                      Рассказать Мунину
                    </Button>
                  </>
                )}
              </section>
            )}
          </aside>
        </div>
      )}
      <TaskDetailModal
        task={openedTask}
        open={Boolean(openedTask)}
        onClose={() => setOpenedTaskId(null)}
      />
    </div>
  );
}
