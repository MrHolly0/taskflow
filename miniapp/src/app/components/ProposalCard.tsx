import { useState } from 'react';
import { IconCheck, IconX, IconAlertTriangle, IconBell, IconCalendar } from '@tabler/icons-react';
import { Proposal, ProposedAction } from '@/lib/hooks/useAssistant';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { buildReminderPresets, isoToZonedDate, isoToZonedTime, zonedInputToIso, isPastReminderTime } from '@/lib/reminderPresets';
import { Button } from '@/app/components/ui/button';
import { Card } from '@/app/components/ui/card';
import { Input } from '@/app/components/ui/input';
import { DateField } from '@/app/components/DateField';
import { TaskResultDescription } from '@/app/components/TaskResultDescription';
import { cn, formatReminderTime, formatPlannedDate } from '@/lib/utils';

// Час по умолчанию для дня исполнения — пользователю незачем указывать
// время, planned_date хранится моментом (OffsetDateTime) только потому,
// что так устроена сущность задачи.
const DEFAULT_PLANNED_TIME = '09:00';

// Спокойный тон намеренно: это не сбой, а объяснение, почему часть сказанного
// не стала действием — без единого слова причины было бы хуже, чем сейчас.
function RejectionsNote({ rejections }: { rejections: string[] }) {
  if (!rejections.length) return null;
  return (
    <div className="space-y-1 pt-2 mt-1 border-t border-border">
      <p className="text-xs text-muted-foreground">Не учтено:</p>
      <ul className="space-y-0.5">
        {rejections.map((reason, i) => (
          <li key={i} className="text-xs text-muted-foreground break-words">
            · {reason}
          </li>
        ))}
      </ul>
    </div>
  );
}

// Г2: время напоминания у create показывается своей строкой прямо в
// карточке подтверждения и правится тут же — без отдельного захода в
// карточку задачи. reminderAt=null у onChange снимает напоминание.
function ReminderLine({
  action,
  onChange,
  disabled,
}: {
  action: ProposedAction;
  onChange: (reminderAt: string | null) => void;
  disabled?: boolean;
}) {
  const { timezone } = useUserTimezone();
  const [editing, setEditing] = useState(false);
  const reminderAt = typeof action.payload.reminder_at === 'string' ? action.payload.reminder_at : undefined;
  const [date, setDate] = useState(() => isoToZonedDate(reminderAt, timezone));
  const [time, setTime] = useState(() => isoToZonedTime(reminderAt, timezone));
  const [error, setError] = useState('');

  const startEditing = () => {
    setDate(isoToZonedDate(reminderAt, timezone));
    setTime(isoToZonedTime(reminderAt, timezone));
    setError('');
    setEditing(true);
  };

  const save = () => {
    if (!date || !time) return;
    const iso = zonedInputToIso(date, time, timezone);
    if (isPastReminderTime(new Date(iso))) {
      setError('Это время уже прошло');
      return;
    }
    onChange(iso);
    setEditing(false);
  };

  const remove = () => {
    onChange(null);
    setEditing(false);
  };

  if (editing) {
    return (
      <div className="ml-6 mt-1 space-y-1.5 rounded-lg border border-border/60 p-2" onClick={(e) => e.stopPropagation()}>
        <div className="flex flex-wrap gap-2">
          {buildReminderPresets(new Date(), timezone).map(preset => <Button key={preset.key} type="button" variant="outline" disabled={disabled} onClick={() => { onChange(preset.fireAt.toISOString()); setEditing(false); }}>{preset.label}</Button>)}
        </div>
        <div className="grid grid-cols-2 gap-1.5">
          <DateField label="Дата напоминания" value={date} onChange={value => { setDate(value); setError(''); }} />
          <Input type="time" value={time} onChange={(e) => { setTime(e.target.value); setError(''); }} className="h-8 text-xs" disabled={!date} />
        </div>
        {error && <p className="text-xs text-destructive">{error}</p>}
        <div className="flex gap-1.5">
          <Button type="button" size="sm" className="h-7 text-xs" disabled={!date || !time || disabled} onClick={save}>
            Сохранить
          </Button>
          {(reminderAt || (action.payload.deadline && action.payload.no_reminder_needed !== true)) ? (
            <Button type="button" variant="ghost" size="sm" className="h-7 text-xs" disabled={disabled} onClick={remove}>
              Не напоминать
            </Button>
          ) : null}
          <Button type="button" variant="ghost" size="sm" className="h-7 text-xs" onClick={() => setEditing(false)}>
            Отмена
          </Button>
        </div>
      </div>
    );
  }

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={(e) => { e.stopPropagation(); startEditing(); }}
      className="ml-6 mt-0.5 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
    >
      <IconBell className="h-3 w-3 flex-shrink-0" />
      {reminderAt ? (
        <>напомнить {formatReminderTime(reminderAt, timezone)} · <span className="underline">изменить</span></>
      ) : action.payload.no_reminder_needed === true ? (
        <>Не напоминать · <span className="underline">изменить</span></>
      ) : action.payload.deadline ? (
        <>По настройкам перед сроком · <span className="underline">изменить</span></>
      ) : (
        <span className="underline">добавить напоминание</span>
      )}
    </button>
  );
}

function ReminderSet({action,onChange,disabled}:{action:ProposedAction;onChange:(value:string|null|string[])=>void;disabled:boolean}) {
  const values=[...new Set([...(typeof action.payload.reminder_at==='string'?[action.payload.reminder_at]:[]),
    ...(Array.isArray(action.payload.reminder_ats)?action.payload.reminder_ats.filter((value):value is string=>typeof value==='string'):[])])];
  if(values.length<=1 && !Array.isArray(action.payload.reminder_ats)) return <ReminderLine action={action} onChange={onChange} disabled={disabled}/>;
  return <div className="space-y-1">{values.map((value,index)=><ReminderLine key={value} action={{...action,payload:{...action.payload,reminder_at:value,reminder_ats:undefined}}}
    disabled={disabled} onChange={next=>onChange(values.flatMap((at,position)=>position===index?(next?[next]:[]):[at]))}/>)}</div>;
}

// В2: день исполнения — прямая пара к ReminderLine. planned_date хранит
// момент, но пользователю нужен только день — время в редакторе не
// показывается, при сохранении подставляется DEFAULT_PLANNED_TIME.
function PlannedDateLine({
  action,
  onChange,
  disabled,
}: {
  action: ProposedAction;
  onChange: (plannedDate: string | null) => void;
  disabled?: boolean;
}) {
  const { timezone } = useUserTimezone();
  const [editing, setEditing] = useState(false);
  const plannedDate = typeof action.payload.planned_date === 'string' ? action.payload.planned_date : undefined;
  const [date, setDate] = useState(() => isoToZonedDate(plannedDate, timezone));

  const startEditing = () => {
    setDate(isoToZonedDate(plannedDate, timezone));
    setEditing(true);
  };

  const save = () => {
    if (!date) return;
    const time = isoToZonedTime(plannedDate, timezone) || DEFAULT_PLANNED_TIME;
    onChange(zonedInputToIso(date, time, timezone));
    setEditing(false);
  };

  const remove = () => {
    onChange(null);
    setEditing(false);
  };

  if (editing) {
    return (
      <div className="ml-6 mt-1 space-y-1.5 rounded-lg border border-border/60 p-2" onClick={(e) => e.stopPropagation()}>
        <DateField label="День работы" value={date} onChange={setDate} />
        <div className="flex gap-1.5">
          <Button type="button" size="sm" className="h-7 text-xs" disabled={!date || disabled} onClick={save}>
            Сохранить
          </Button>
          {plannedDate && (
            <Button type="button" variant="ghost" size="sm" className="h-7 text-xs" disabled={disabled} onClick={remove}>
              Убрать
            </Button>
          )}
          <Button type="button" variant="ghost" size="sm" className="h-7 text-xs" onClick={() => setEditing(false)}>
            Отмена
          </Button>
        </div>
      </div>
    );
  }

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={(e) => { e.stopPropagation(); startEditing(); }}
      className="ml-6 mt-0.5 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
    >
      <IconCalendar className="h-3 w-3 flex-shrink-0" />
      {plannedDate ? (
        <>Предлагаю заняться {formatPlannedDate(plannedDate, timezone)} · <span className="underline">изменить</span></>
      ) : (
        <span className="underline">Выбрать день работы</span>
      )}
    </button>
  );
}

export function ProposalCard({
  proposal,
  onToggle,
  onSelect,
  onReminderChange,
  onPlannedDateChange,
  onApply,
  onReject,
  applying,
  rejecting,
  className,
}: {
  proposal: Proposal;
  onToggle: (ordinal: number, accepted: boolean) => void;
  onSelect: (ordinal: number) => void;
  onReminderChange: (ordinal: number, reminderAt: string | null | string[]) => void;
  onPlannedDateChange: (ordinal: number, plannedDate: string | null) => void;
  onApply: (persistentOrdinals: number[]) => void;
  onReject: () => void;
  applying: boolean;
  rejecting: boolean;
  className?: string;
}) {
  // Б1: настойчивость ставит человек — галочка тут же, в карточке
  // подтверждения, не отдельным заходом в карточку задачи после создания.
  // Модель этого поля не видит: чисто локальное состояние до "Применить".
  const [persistentOrdinals, setPersistentOrdinals] = useState<Set<number>>(new Set());
  const togglePersistent = (ordinal: number, checked: boolean) => {
    setPersistentOrdinals((prev) => {
      const next = new Set(prev);
      if (checked) next.add(ordinal); else next.delete(ordinal);
      return next;
    });
  };
  const allowsPersistent = (action: ProposedAction) => action.payload.no_reminder_needed !== true
    && Boolean(action.payload.reminder_at || (Array.isArray(action.payload.reminder_ats)&&action.payload.reminder_ats.length) || action.payload.deadline);
  const applyWithPersistent = () => onApply(Array.from(persistentOrdinals).filter(ordinal =>
    proposal.actions.some(action => action.ordinal === ordinal && action.accepted && allowsPersistent(action))));
  const isDegraded = proposal.status === 'FAILED' && !proposal.id;
  const isClarification = !proposal.actions.length && !!proposal.clarification && !isDegraded;

  if (isDegraded) {
    return (
      <Card className={cn('px-4 py-3 text-sm flex items-start gap-2 border-amber-400/40 bg-amber-50 dark:bg-amber-950/30', className)}>
        <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0 text-amber-600" />
        <span className="min-w-0 break-words">{proposal.clarification ?? 'Не удалось разобрать сообщение — сохранил его как отдельную задачу целиком.'}</span>
      </Card>
    );
  }

  if (isClarification) {
    return (
      <Card className={cn('px-4 py-3 text-sm break-words', className)}>
        {proposal.clarification}
      </Card>
    );
  }

  if (!proposal.actions.length) {
    return (
      <Card className={cn('px-4 py-3 text-sm text-muted-foreground', className)}>
        Не нашел, что предложить по этому сообщению.
        <RejectionsNote rejections={proposal.rejections} />
      </Card>
    );
  }

  if (proposal.exclusive) {
    return (
      <Card className={cn('px-4 py-3 space-y-3', className)}>
        <div className="space-y-1">
          <p className="text-sm font-medium">Выберите, что имелось в виду</p>
          {proposal.ambiguityReason && (
            <p className="text-xs text-muted-foreground">{proposal.ambiguityReason}</p>
          )}
        </div>
        <div className="space-y-1.5">
          {proposal.actions.map((action) => (
            <label
              key={action.ordinal}
              className="flex items-start gap-2 text-sm cursor-pointer select-none"
            >
              <input
                type="radio"
                name={`proposal-${proposal.id}-alternatives`}
                checked={action.accepted}
                disabled={applying || rejecting}
                onChange={() => onSelect(action.ordinal)}
                className="mt-0.5 flex-shrink-0"
              />
              <span className="min-w-0 break-words">{action.summary}</span>
            </label>
          ))}
        </div>
        <div className="flex gap-2 pt-1">
          <Button size="sm" onClick={applyWithPersistent} disabled={applying || rejecting} className="flex-1 gap-1.5">
            <IconCheck className="h-3.5 w-3.5" />
            Применить
          </Button>
          <Button size="sm" variant="outline" onClick={onReject} disabled={applying || rejecting} className="flex-1 gap-1.5">
            <IconX className="h-3.5 w-3.5" />
            Отклонить
          </Button>
        </div>
        <RejectionsNote rejections={proposal.rejections} />
      </Card>
    );
  }

  return (
    <Card className={cn('px-4 py-3 space-y-3', className)}>
      <p className="text-sm font-medium">Предлагаю:</p>
      <div className="space-y-1.5">
        {proposal.actions.map((action) => (
          <div key={action.ordinal}>
            <label className="flex items-start gap-2 text-sm cursor-pointer select-none">
              <input
                type="checkbox"
                checked={action.accepted}
                disabled={applying || rejecting}
                onChange={(e) => onToggle(action.ordinal, e.target.checked)}
                className="mt-0.5 flex-shrink-0"
              />
              <span className={cn('min-w-0 break-words', !action.accepted && 'text-muted-foreground line-through')}>
                {action.summary}
              </span>
            </label>
            {action.type==='CREATE' && typeof action.payload.description==='string' && <div className="ml-6 mt-2"><TaskResultDescription description={action.payload.description} title={typeof action.payload.title==='string'?action.payload.title:undefined}/></div>}
            {action.type === 'CREATE' && typeof action.payload.group === 'string' && <p className="ml-6 mt-1 text-sm text-muted-foreground">Группа: {action.payload.group}</p>}
            {action.payload.deadline_time_inferred === true && <p className="ml-6 mt-1 text-sm text-muted-foreground">Срок до конца дня: точное время не указано.</p>}
            {action.type === 'CREATE' && action.accepted && (
              <>
                <ReminderSet
                  action={action}
                  onChange={(reminderAt) => onReminderChange(action.ordinal, reminderAt)}
                  disabled={applying || rejecting}
                />
                <PlannedDateLine
                  action={action}
                  onChange={(plannedDate) => onPlannedDateChange(action.ordinal, plannedDate)}
                  disabled={applying || rejecting}
                />
                <label className="ml-6 mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={allowsPersistent(action) && persistentOrdinals.has(action.ordinal)}
                    disabled={!allowsPersistent(action) || applying || rejecting}
                    onChange={(e) => togglePersistent(action.ordinal, e.target.checked)}
                    className="flex-shrink-0"
                  />
                  напоминать повторно, если не отмечу
                </label>
              </>
            )}
          </div>
        ))}
      </div>
      <div className="flex gap-2 pt-1">
        <Button size="sm" onClick={applyWithPersistent} disabled={applying || rejecting} className="flex-1 gap-1.5">
          <IconCheck className="h-3.5 w-3.5" />
          Применить
        </Button>
        <Button size="sm" variant="outline" onClick={onReject} disabled={applying || rejecting} className="flex-1 gap-1.5">
          <IconX className="h-3.5 w-3.5" />
          Отклонить
        </Button>
      </div>
      <RejectionsNote rejections={proposal.rejections} />
    </Card>
  );
}
