import { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconCheck, IconChevronDown } from '@tabler/icons-react';
import {
  useFocusTasks,
  useUpcomingFocusTasks,
  useTasksList,
  usePlannedDateSuggestion,
  type TaskResponse,
} from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useDisplayName } from '@/lib/hooks/useSettings';
import { useMinuteTick } from '@/lib/hooks/useMinuteTick';
import { isSameZonedDay } from '@/lib/utils';
import { useTaskUi } from '@/lib/task-ui';
import { plural } from '@/lib/plural';
import { TaskRow } from '@/app/components/TaskRow';
import { CaptureCard } from '@/app/components/CaptureCard';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { EmailBindBanner } from '@/app/components/EmailBindBanner';

function FocusRow({
  task,
  primary,
  onOpen,
  suggest,
}: {
  task: TaskResponse;
  primary: boolean;
  onOpen: () => void;
  suggest: boolean;
}) {
  usePlannedDateSuggestion(task.id, suggest && !task.deadline && !task.plannedDate);
  return <TaskRow task={task} variant="focus" primary={primary} onOpen={onOpen} />;
}
export function FocusPage() {
  const name = useDisplayName();
  const [minutes, setMinutes] = useState<number>();
  const [details, setDetails] = useState(false);
  const [selected, setSelected] = useState<TaskResponse | null>(null);
  const { data: focus = [], isLoading, error } = useFocusTasks(minutes);
  const { data: upcoming = [], isLoading: upcomingLoading } = useUpcomingFocusTasks(
    !isLoading && !error && focus.length === 0,
    minutes
  );
  const { data: all = [] } = useTasksList();
  const { timezone, isReady, error: timezoneError } = useUserTimezone();
  const revealIds = useTaskUi((s) => s.revealIds);
  const revealedAt = useTaskUi((s) => s.revealedAt);
  useMinuteTick();
  const extra =
    Date.now() - revealedAt < 8000
      ? all.filter(
          (t) => revealIds.includes(t.id) && t.status !== 'DONE' && t.status !== 'CANCELLED'
        )
      : [];
  const source = focus.length ? focus : upcoming;
  const tasks = [...extra, ...source.filter((t) => !extra.some((e) => e.id === t.id))].slice(0, 3);
  const done = all.filter(
    (t) =>
      t.status === 'DONE' && t.completedAt && isSameZonedDay(t.completedAt, new Date(), timezone)
  );
  const started = all.filter(
    (t) => t.startedAt && isSameZonedDay(t.startedAt, new Date(), timezone)
  ).length;
  const moved = all.filter(
    (t) => t.plannedDateSetAt && isSameZonedDay(t.plannedDateSetAt, new Date(), timezone)
  ).length;
  const next = tasks.find((t) => t.status === 'IN_PROGRESS') || tasks[0];
  if (error || timezoneError)
    return (
      <div className="munin-page page-state">
        <p>Не удалось загрузить задачи.</p>
        <Link to="/all">Открыть все задачи</Link>
      </div>
    );
  if (isLoading || !isReady || (!focus.length && upcomingLoading))
    return <div className="munin-page page-state">Загружаем задачи…</div>;
  return (
    <div className="munin-page focus-layout">
      <section className="focus-main">
        <p className="focus-greeting">Привет, {name}.</p>
        <h1 className="focus-title">
          {tasks.length ? (
            <>
              Сейчас у тебя
              <br />
              <strong>
                {tasks.length}{' '}
                {plural(tasks.length, ['главное дело', 'главных дела', 'главных дел'])}.
              </strong>
            </>
          ) : (
            <>
              На сегодня
              <br />
              <strong>можно выдохнуть.</strong>
            </>
          )}
        </h1>
        <p className="focus-date">
          {new Date().toLocaleDateString('ru-RU', {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
            timeZone: timezone,
          })}
        </p>
        <EmailBindBanner />
        <div className="focus-budget" role="group" aria-label="Сколько времени есть?">
          <span>Сколько времени есть?</span>
          {[
            [15, '15 мин'],
            [30, '30 мин'],
            [60, '1 час'],
          ].map(([value, label]) => (
            <button
              key={value}
              aria-pressed={minutes === value}
              onClick={() => {
                setMinutes(minutes === value ? undefined : Number(value));
                localStorage.setItem('taskflow_time_budget_used', '1');
              }}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="focus-section-head">
          <h2>{focus.length ? 'Фокус сейчас' : tasks.length ? 'Дальше по плану' : 'Итоги дня'}</h2>
          <button onClick={() => setDetails(!details)} aria-expanded={details}>
            <IconCheck />
            {done.length} готово
            {next
              ? ` · ${next.status === 'IN_PROGRESS' ? 'в работе' : 'следующее'}${next.estimateMinutes ? ` — ~${next.estimateMinutes} мин` : ''}`
              : ''}
            <IconChevronDown />
          </button>
        </div>
        {details && (
          <div className="day-summary">
            <p>
              {done.length
                ? `${done.length} ${plural(done.length, ['дело', 'дела', 'дел'])} уже позади.`
                : 'Можно начать с одного небольшого дела.'}
            </p>
            <small>
              Начато {started} · отложено {moved}
            </small>
            <Link to="/stats">Посмотреть свой прогресс →</Link>
          </div>
        )}
        <div>
          {tasks.map((task, i) => (
            <FocusRow
              key={task.id}
              task={task}
              primary={i === 0}
              onOpen={() => setSelected(task)}
              suggest={focus.length > 0}
            />
          ))}
        </div>
        {!tasks.length && (
          <div className="empty-task-list">
            <p>Добавь мысли на потом или посмотри весь список.</p>
            <Link to="/all">Все задачи →</Link>
          </div>
        )}
        <p className="task-interaction-hint">
          <span className="mouse-hint">ПКМ по задаче или нажми ⋯ для быстрых действий</span>
          <span className="touch-hint">Удерживай задачу или нажми ⋯ для быстрых действий</span>
        </p>
      </section>
      <CaptureCard />
      <TaskDetailModal
        task={selected ? all.find((t) => t.id === selected.id) || selected : null}
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        showFocusHint={focus.length > 0}
      />
    </div>
  );
}
