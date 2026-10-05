import { useEffect, useRef, useState } from 'react';
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
import { useUpdateTask } from '@/lib/hooks/useTasks';
import { useEffectiveVoiceMode } from '@/lib/hooks/useVoiceMode';
import { useVoiceRecording } from '@/lib/hooks/useVoiceRecording';
import { ProposalCard } from '@/app/components/ProposalCard';
import { VoiceRecorderTrigger } from '@/app/components/VoiceRecorderTrigger';
import { VoiceRecordingBar } from '@/app/components/VoiceRecordingBar';
import { Button } from '@/app/components/ui/button';
import { Card } from '@/app/components/ui/card';
import { cn } from '@/lib/utils';
import '@/styles/assistant.css';

type Entry =
  | { kind: 'user'; text: string }
  | { kind: 'pending'; localId: string }
  | { kind: 'proposal'; proposal: Proposal; localId: string }
  | { kind: 'applied'; result: ApplyResult; localId: string }
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

  const useExample = (text: string) => {
    setInput(text);
    inputField.current?.focus();
  };
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
      onSuccess: (proposal) => {
        replaceEntry(localId, { kind: 'proposal', proposal, localId });
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
        replaceEntry(localId, { kind: 'applied', result, localId });
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
      onError: () => replaceEntry(localId, { kind: 'error', message: errorMessage(undefined), localId }),
    });
  };

  const reject = (proposal: Proposal, localId: string) => {
    if (!proposal.id) return;
    rejectProposal.mutate(proposal.id, {
      onSuccess: () => replaceEntry(localId, { kind: 'rejected', localId }),
      onError: () => replaceEntry(localId, { kind: 'error', message: errorMessage(undefined), localId }),
    });
  };

  return (
    <div className="munin-assistant">
      <header className="munin-chat-heading">
        <h1>Разберём твои дела.</h1>
        <p>Расскажи своими словами. Изменения подтверждаешь ты.</p>
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
            <p>Можно добавить дела, перенести планы или рассказать, что уже готово. Мунин предложит изменения, а ты проверишь их перед применением.</p>
            <div className="munin-chat-examples" aria-label="Примеры обращений">
              {['Завтра нужно отправить документы', 'Позвонил Саше, задача готова', 'Перенеси встречу на пятницу'].map((example) => (
                <button type="button" key={example} onClick={() => useExample(example)}>{example}</button>
              ))}
            </div>
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
                <ApplyResultCard result={entry.result} />
              )}

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
          <div className="grid flex-1">
            <textarea
              key="input"
              ref={inputField}
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
    </div>
  );
}

function statusLabel(status: string): string | null {
  switch (status) {
    case 'EXPIRED':
      return 'Предложение просрочено — прошло больше суток, ничего не применено.';
    case 'FAILED':
      return null; // отрисовывается отдельно, через clarification
    default:
      return null;
  }
}

function ApplyResultCard({ result }: { result: ApplyResult }) {
  const expiredLabel = statusLabel(result.status);
  const failures = result.outcomes.filter((o) => !o.success);

  return (
    <Card className="max-w-[85%] px-4 py-3 text-sm space-y-1.5">
      <p className="font-medium">
        {expiredLabel ?? `Применено ${result.appliedCount} из ${result.totalCount}.`}
      </p>
      {failures.length > 0 && (
        <ul className="space-y-1 text-muted-foreground">
          {failures.map((f) => (
            <li key={f.ordinal} className="break-words">
              {f.summary} — {f.error}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
