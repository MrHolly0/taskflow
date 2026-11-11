import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTaskUi } from '@/lib/task-ui';
import { IconSend, IconAlertTriangle } from '@tabler/icons-react';
import { motion, AnimatePresence, useReducedMotion } from 'motion/react';
import {
  useSendAssistantMessage,
  useSetActionAccepted,
  useSelectAlternative,
  useUpdateActionReminder,
  useUpdateActionPlannedDate,
  useApplyProposal,
  useRejectProposal,
  Proposal,
  ApplyResult,
} from '@/lib/hooks/useAssistant';
import { useUpdateTask, useTaskPage, useGroups, type TaskResponse } from '@/lib/hooks/useTasks';
import { buildAssistantRecommendations } from '@/lib/assistant-recommendations';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useMinuteTick } from '@/lib/hooks/useMinuteTick';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { toast } from 'sonner';
import { useEffectiveVoiceMode } from '@/lib/hooks/useVoiceMode';
import { useVoiceRecording } from '@/lib/hooks/useVoiceRecording';
import { AssistantResultCard } from '@/app/components/AssistantResultCard';
import { ProposalCard } from '@/app/components/ProposalCard';
import { VoiceRecorderTrigger } from '@/app/components/VoiceRecorderTrigger';
import { VoiceRecordingBar } from '@/app/components/VoiceRecordingBar';
import { Button } from '@/app/components/ui/button';
import { Card } from '@/app/components/ui/card';
import { cn, formatDeadline } from '@/lib/utils';
import { useStore } from '@/lib/store';
import '@/styles/assistant.css';

type Entry =
  | { kind: 'user'; text: string }
  | { kind: 'pending'; localId: string }
  | { kind: 'proposal'; proposal: Proposal; localId: string; error?: string }
  | { kind: 'applied'; result: ApplyResult; proposal: Proposal; localId: string }
  | { kind: 'rejected'; localId: string }
  | { kind: 'error'; message: string; localId: string };

let entryCounter = 0;
function nextId() {
  entryCounter += 1;
  return `e${entryCounter}`;
}

function isRateLimited(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'response' in error
    && (error as { response?: { status?: number } }).response?.status === 429;
}

function errorMessage(error: unknown): string {
  if (isRateLimited(error)) {
    return 'Слишком много обращений подряд — подождите минуту и попробуйте снова.';
  }
  return 'Не получилось связаться с ассистентом. Попробуйте еще раз.';
}

export function AssistantPage() {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [input, setInput] = useState('');
  const navigate = useNavigate();
  const reveal = useTaskUi(state => state.reveal);
  const [recommendedSection, setRecommendedSection] = useState<'reminders' | 'group' | 'deadline' | 'planned'>();
  const [recommendedTask, setRecommendedTask] = useState<TaskResponse | null>(null);
  const candidates = useTaskPage({ status: 'active', order: 'recommendation', size: 8 }, 0, entries.length === 0);
  const { data: groups = [] } = useGroups();
  useMinuteTick();
  const { timezone } = useUserTimezone();
  const userId = useStore(state => state.user?.id);
  const hiddenKey = `munin-hidden-recommendations:${userId ?? 'local'}`;
  const [hiddenRecommendations, setHiddenRecommendations] = useState<string[]>([]);
  useEffect(() => {
    try {
      const saved: unknown = JSON.parse(localStorage.getItem(hiddenKey) ?? '[]');
      setHiddenRecommendations(Array.isArray(saved) ? saved.filter((item): item is string => typeof item === 'string').slice(-500) : []);
    }
    catch { setHiddenRecommendations([]); }
  }, [hiddenKey]);
  const dismissRecommendation = (id: string) => {
    const next = [...hiddenRecommendations, id].slice(-500); setHiddenRecommendations(next);
    try { localStorage.setItem(hiddenKey, JSON.stringify(next)); } catch { /* Скрытие действует до закрытия страницы. */ }
  };
  const recommendations = buildAssistantRecommendations(candidates.data?.content ?? [], groups.length > 0, new Date(), timezone)
    .filter(item => !hiddenRecommendations.includes(`${item.task.id}:${item.kind}`)).slice(0, 3);
  const conversation = useRef<HTMLDivElement>(null);
  const inputField = useRef<HTMLTextAreaElement>(null);
  const previousCount = useRef(0);
  const followReplies = useRef(true);
  const reduceMotion = useReducedMotion();
  const lastKind = entries[entries.length - 1]?.kind;

  useEffect(() => {
    const log = conversation.current;
    if (!log) return;
    if (entries.length > previousCount.current || followReplies.current) {
      log.scrollTop = log.scrollHeight;
    }
    previousCount.current = entries.length;
  }, [entries.length, lastKind]);

  const sendMessage = useSendAssistantMessage();
  const setActionAccepted = useSetActionAccepted();
  const selectAlternative = useSelectAlternative();
  const updateActionReminder = useUpdateActionReminder();
  const updateActionPlannedDate = useUpdateActionPlannedDate();
  const applyProposal = useApplyProposal();
  const rejectProposal = useRejectProposal();
  const updateTask = useUpdateTask();
  const voiceMode = useEffectiveVoiceMode();
  const recording = useVoiceRecording(voiceMode, (file) => send({ file }));

  const replaceEntry = (localId: string, entry: Entry) => {
    setEntries((prev) => prev.map((e) => ('localId' in e && e.localId === localId ? entry : e)));
  };

  const send = (payload: { text?: string; file?: File }) => {
    if (sendMessage.isPending) return;
    const localId = nextId();
    const userText = payload.text ?? '🎤 Голосовое сообщение';
    setEntries((prev) => [...prev, { kind: 'user', text: userText }, { kind: 'pending', localId }]);
    setInput('');

    sendMessage.mutate(payload, {
      onSuccess: ({proposal,applied}) => {
        replaceEntry(localId,applied?{kind:'applied',result:applied,proposal,localId}:{kind:'proposal',proposal,localId});
      },
      onError: (error) => {
        replaceEntry(localId, { kind: 'error', message: errorMessage(error), localId });
      },
    });
  };

  const handleSubmit = () => {
    const text = input.trim();
    if (!text) return;
    send({ text });
  };

  const toggleAction = (proposal: Proposal, localId: string, ordinal: number, accepted: boolean) => {
    if (!proposal.id) return;
    setActionAccepted.mutate(
      { proposalId: proposal.id, ordinal, accepted },
      {
        onSuccess: (updated) => replaceEntry(localId, { kind: 'proposal', proposal: updated, localId }),
      }
    );
  };

  const selectAction = (proposal: Proposal, localId: string, ordinal: number) => {
    if (!proposal.id) return;
    selectAlternative.mutate(
      { proposalId: proposal.id, ordinal },
      {
        onSuccess: (updated) => replaceEntry(localId, { kind: 'proposal', proposal: updated, localId }),
      }
    );
  };

  const changeReminder = (proposal: Proposal, localId: string, ordinal: number, reminderAt: string | null) => {
    if (!proposal.id) return;
    updateActionReminder.mutate(
      { proposalId: proposal.id, ordinal, reminderAt },
      {
        onSuccess: (updated) => replaceEntry(localId, { kind: 'proposal', proposal: updated, localId }),
      }
    );
  };

  const changePlannedDate = (proposal: Proposal, localId: string, ordinal: number, plannedDate: string | null) => {
    if (!proposal.id) return;
    updateActionPlannedDate.mutate(
      { proposalId: proposal.id, ordinal, plannedDate },
      {
        onSuccess: (updated) => replaceEntry(localId, { kind: 'proposal', proposal: updated, localId }),
      }
    );
  };

  const apply = (proposal: Proposal, localId: string, persistentOrdinals: number[]) => {
    if (!proposal.id) return;
    applyProposal.mutate(proposal.id, {
      onSuccess: (result) => {
        replaceEntry(localId, { kind: 'applied', result, proposal, localId });
        // Б1: галочка «настойчиво» из карточки предложения применяется
        // здесь, отдельным вызовом, а не как часть самого действия — модель
        // это поле не видит, оно ставится только что созданной задаче,
        // о чьём id мы узнаём только сейчас, из результата apply().
        for (const outcome of result.outcomes) {
          if (outcome.success && outcome.taskId && persistentOrdinals.includes(outcome.ordinal)) {
            updateTask.mutate({ id: outcome.taskId, persistentReminder: true });
          }
        }
      },
      onError: () => replaceEntry(localId, { kind: 'proposal', proposal, error: 'Не удалось применить. Выбранные действия сохранены — попробуй ещё раз.', localId }),
    });
  };

  const reject = (proposal: Proposal, localId: string) => {
    if (!proposal.id) return;
    rejectProposal.mutate(proposal.id, {
      onSuccess: () => replaceEntry(localId, { kind: 'rejected', localId }),
      onError: () => replaceEntry(localId, { kind: 'proposal', proposal, error: 'Не удалось отклонить предложение. Попробуй ещё раз.', localId }),
    });
  };

  return (
    <div className="munin-assistant">
      <header className="munin-chat-heading">
        <h1>Разберём твои дела.</h1>
        <p>Расскажи своими словами, Мунин разберётся с делами</p>
      </header>

      <div
        className="munin-conversation"
        ref={conversation}
        role="log"
        aria-label="Переписка с Мунином"
        aria-live="polite"
        aria-relevant="additions"
        onScroll={() => {
          const log = conversation.current;
          if (log) followReplies.current = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
        }}
      >
        {entries.length === 0 && (
          <div className="munin-chat-intro">
            <p>Можно добавить дела или рассказать, что уже готово. Если что-то не так, действие можно отменить</p>
            <section className="assistant-recommendations" aria-label="Рекомендации по твоим задачам">
              {recommendations.length > 0 && <h2>Что можно сделать сейчас</h2>}
              {recommendations.map(item => <article key={item.task.id}>
                <strong>{item.task.title}</strong>
                {item.task.deadline && <p>Срок: {formatDeadline(item.task.deadline, timezone, item.task.status)}</p>}
                <p>{item.reason}</p>
                <Button variant="outline" disabled={updateTask.isPending} onClick={() => {
                  if (item.kind === 'continue') { reveal([item.task.id]); navigate(`/?task=${item.task.id}`); return; }
                  if (item.kind !== 'start') {
                    setRecommendedSection(item.kind === 'reminder' ? 'reminders' : item.kind === 'group' ? 'group' : item.kind === 'planned' ? 'planned' : 'deadline');
                    setRecommendedTask(item.task); return;
                  }
                  updateTask.mutate({ id: item.task.id, status: 'IN_PROGRESS' }, {
                    onSuccess: () => toast.success('Задача в работе', { description: item.task.title }),
                    onError: () => toast.error('Не получилось взять задачу в работу. Попробуй ещё раз.'),
                  });
                }}>{item.label}</Button>
                <Button variant="ghost" onClick={() => dismissRecommendation(`${item.task.id}:${item.kind}`)}>Не предлагать это действие</Button>
              </article>)}
              {candidates.isError && <p role="alert">Не удалось загрузить рекомендации. <button onClick={() => void candidates.refetch()}>Повторить</button></p>}
            </section>
          </div>
        )}
        <AnimatePresence initial={false}>
          {entries.map((entry, i) => (
            <motion.div
              key={'localId' in entry ? entry.localId : `user-${i}`}
              initial={reduceMotion ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: reduceMotion ? 0 : 0.18 }}
              className="munin-chat-entry"
            >
              {entry.kind === 'user' && (
                <div className="munin-user-entry">
                  <p className="munin-message-meta">Ты</p>
                  <p className="munin-user-message">{entry.text}</p>
                </div>
              )}

              {entry.kind === 'pending' && (
                <Card className="max-w-[85%] px-4 py-3 text-sm text-muted-foreground">
                  Обрабатываю обращение…
                </Card>
              )}

              {entry.kind === 'error' && (
                <Card className="max-w-[85%] border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive flex items-start gap-2">
                  <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                  <span className="min-w-0 break-words">{entry.message}</span>
                </Card>
              )}

              {entry.kind === 'rejected' && (
                <Card className="max-w-[85%] px-4 py-3 text-sm text-muted-foreground">
                  Предложение отклонено.
                </Card>
              )}

              {entry.kind === 'applied' && (
                <AssistantResultCard result={entry.result} proposal={entry.proposal} className="munin-chat-result" />
              )}

              {entry.kind === 'proposal' && entry.error && <p className="text-sm text-destructive" role="alert">{entry.error}</p>}
              {entry.kind === 'proposal' && (
                <ProposalCard
                  proposal={entry.proposal}
                  onToggle={(ordinal, accepted) => toggleAction(entry.proposal, entry.localId, ordinal, accepted)}
                  onSelect={(ordinal) => selectAction(entry.proposal, entry.localId, ordinal)}
                  onReminderChange={(ordinal, reminderAt) => changeReminder(entry.proposal, entry.localId, ordinal, reminderAt)}
                  onPlannedDateChange={(ordinal, plannedDate) => changePlannedDate(entry.proposal, entry.localId, ordinal, plannedDate)}
                  onApply={(persistentOrdinals) => apply(entry.proposal, entry.localId, persistentOrdinals)}
                  onReject={() => reject(entry.proposal, entry.localId)}
                  applying={applyProposal.isPending}
                  rejecting={rejectProposal.isPending}
                  className="munin-chat-proposal"
                />
              )}
            </motion.div>
          ))}
        </AnimatePresence>
      </div>

      <div className="munin-composer">
        <div className="munin-composer-field">
          {/* Textarea и полоса записи лежат в одной ячейке грида — высота слота
              определяется большей из них и не меняется при старте записи, иначе
              кнопка справа подпрыгивает в момент, когда палец уже на ней. */}
          <div className="grid grid-cols-1 min-w-0 flex-1">
            <textarea
              key="input"
              ref={inputField}
              wrap="soft"
              aria-label="Сообщение Мунину"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  handleSubmit();
                }
              }}
              placeholder="Что нужно сделать или что изменилось?"
              className={cn('munin-message-input col-start-1 row-start-1 w-full min-h-16 max-h-32 resize-none', recording.isRecording && 'invisible pointer-events-none')}
              aria-hidden={recording.isRecording}
            />
            <div
              className={cn('col-start-1 row-start-1 flex items-center', !recording.isRecording && 'invisible pointer-events-none')}
              aria-hidden={!recording.isRecording}
            >
              <VoiceRecordingBar key="bar" recording={recording} />
            </div>
          </div>
          <VoiceRecorderTrigger key="trigger" recording={recording} disabled={sendMessage.isPending} />
          {!recording.isRecording && (
            <Button key="send" size="icon" onClick={handleSubmit} disabled={!input.trim() || sendMessage.isPending} aria-label="Отправить сообщение" title="Отправить" className="munin-send-message">
              <IconSend className="h-4 w-4" />
            </Button>
          )}
        </div>
        <p className="munin-composer-hint">Enter: отправить · Shift + Enter: новая строка</p>
      </div>
      <TaskDetailModal task={recommendedTask ? candidates.data?.content.find(task => task.id === recommendedTask.id) ?? recommendedTask : null}
        initialSection={recommendedSection} open={Boolean(recommendedTask)} onClose={() => setRecommendedTask(null)} />
    </div>
  );
}
