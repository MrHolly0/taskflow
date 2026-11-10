import { useState, useRef, useEffect } from 'react';
import { IconSend, IconSparkles, IconArrowLeft, IconPaperclip, IconAlertTriangle, IconMicrophone } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { useQuickAdd, useSetActionAccepted, useSelectAlternative, useUpdateActionReminder, useUpdateActionPlannedDate, useApplyProposal, useRejectProposal, Proposal } from '@/lib/hooks/useAssistant';
import { toast } from 'sonner';
import { showTaskReceipt } from '@/app/components/TaskReceipt';
import type { ApplyResult } from '@/lib/hooks/useAssistant';
import { useUpdateTask } from '@/lib/hooks/useTasks';
import { useEffectiveVoiceMode } from '@/lib/hooks/useVoiceMode';
import { useVoiceRecording } from '@/lib/hooks/useVoiceRecording';
import { ProposalCard } from '@/app/components/ProposalCard';
import { VoiceRecorderTrigger } from '@/app/components/VoiceRecorderTrigger';
import { VoiceRecordingBar } from '@/app/components/VoiceRecordingBar';
import { Button } from '@/app/components/ui/button';
import { Textarea } from '@/app/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import { cn } from '@/lib/utils';
import { shortcutLabel } from '@/lib/shortcuts';
import { useTaskUi } from '@/lib/task-ui';

type Phase = 'input' | 'processing' | 'confirm';

interface QuickInputModalProps {
  open: boolean;
  onClose: () => void;
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

export function QuickInputModal({ open, onClose }: QuickInputModalProps) {
  const quickAdd = useQuickAdd();
  const setActionAccepted = useSetActionAccepted();
  const selectAlternative = useSelectAlternative();
  const updateActionReminder = useUpdateActionReminder();
  const updateActionPlannedDate = useUpdateActionPlannedDate();
  const applyProposal = useApplyProposal();
  const updateTask = useUpdateTask();
  const rejectProposal = useRejectProposal();

  const [phase, setPhase] = useState<Phase>('input');
  const [text, setText] = useState('');
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const sending = useRef(false);
  const reveal = useTaskUi(s => s.reveal);
  const voiceMode = useEffectiveVoiceMode();
  const recording = useVoiceRecording(voiceMode, (file) => send({ file }));

  useEffect(() => {
    const focusTimer = open ? setTimeout(() => textareaRef.current?.focus(), 100) : undefined;
    return () => clearTimeout(focusTimer);
  }, [open]);

  const finishApply = (result: ApplyResult, origin: Proposal) => {
    const succeeded = result.outcomes.filter(outcome => outcome.success);
    if (!succeeded.length) {
      const message = result.outcomes.map(outcome => outcome.error || outcome.summary).join('. ') || 'Ни одно изменение не применено.';
      setError(message);
      setPhase('input');
      toast.error('Изменения не применены', { description: message, duration: 12000 });
      return;
    }
    onClose();
    reveal(succeeded.flatMap(outcome => outcome.taskId ? [outcome.taskId] : []).slice(0, 20));
    showTaskReceipt(result, origin);
    setPhase('input');
    setText('');
    setProposal(null);
    setError(null);
  };

  const send = (payload: { text?: string; file?: File }) => {
    if (sending.current) return;
    sending.current = true;
    setPhase('processing');
    setError(null);
    quickAdd.mutate(payload, {
      onSuccess: (result) => {
        sending.current = false;
        if (result.applied) {
          finishApply(result.applied, result.proposal);
        } else {
          setProposal(result.proposal);
          setPhase('confirm');
        }
      },
      onError: (err) => {
        sending.current = false;
        setError(errorMessage(err));
        setPhase('input');
      },
    });
  };

  const handleSubmit = () => {
    if (!text.trim()) return;
    send({ text: text.trim() });
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = '';
    send({ file });
  };

  const toggleAction = (ordinal: number, accepted: boolean) => {
    if (!proposal?.id) return;
    setActionAccepted.mutate(
      { proposalId: proposal.id, ordinal, accepted },
      { onSuccess: (updated) => setProposal(updated) }
    );
  };

  const selectAction = (ordinal: number) => {
    if (!proposal?.id) return;
    selectAlternative.mutate(
      { proposalId: proposal.id, ordinal },
      { onSuccess: (updated) => setProposal(updated) }
    );
  };

  const changeReminder = (ordinal: number, reminderAt: string | null) => {
    if (!proposal?.id) return;
    updateActionReminder.mutate(
      { proposalId: proposal.id, ordinal, reminderAt },
      { onSuccess: (updated) => setProposal(updated) }
    );
  };

  const changePlannedDate = (ordinal: number, plannedDate: string | null) => {
    if (!proposal?.id) return;
    updateActionPlannedDate.mutate(
      { proposalId: proposal.id, ordinal, plannedDate },
      { onSuccess: (updated) => setProposal(updated) }
    );
  };

  const applyCurrent = (persistentOrdinals: number[]) => {
    if (!proposal?.id) return;
    applyProposal.mutate(proposal.id, {
      onSuccess: (result) => {
        finishApply(result, proposal);
        for (const outcome of result.outcomes) {
          if (outcome.success && outcome.taskId && persistentOrdinals.includes(outcome.ordinal)) {
            updateTask.mutate({ id: outcome.taskId, persistentReminder: true });
          }
        }
      },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  const rejectCurrent = () => {
    if (!proposal?.id) return;
    rejectProposal.mutate(proposal.id, {
      onSuccess: () => { setPhase('input'); setProposal(null); },
      onError: (err) => setError(errorMessage(err)),
    });
  };

  const handleBack = () => { setPhase('input'); setProposal(null); setError(null); };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmit();
  };

  const handleClose = () => { recording.finishCancel(); onClose(); };

  return (
    <Dialog open={open} onOpenChange={(o: boolean) => !o && handleClose()}>
      <DialogContent
        className={cn(
          'sm:max-w-md w-full overflow-y-auto',
          'self-start max-h-[calc(100dvh-2rem)]',
          'sm:self-center sm:max-h-[90dvh]'
        )}
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <IconSparkles className="h-5 w-5 text-primary" />
            Что записать?
          </DialogTitle>
        </DialogHeader>

        <div className="min-w-0 space-y-4">
          <AnimatePresence mode="wait">
            {phase === 'input' && (
              <motion.div key="input" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                {/* Обе части лежат в одной ячейке грида, чтобы высота блока определялась
                    большей из них и не менялась при старте записи — иначе строка с кнопкой
                    подпрыгивает в момент, когда палец уже прижат к ней. */}
                <div className="grid grid-cols-1 min-w-0">
                  <div
                    className={cn('col-start-1 row-start-1 space-y-3', recording.isRecording && 'invisible pointer-events-none')}
                    aria-hidden={recording.isRecording}
                  >
                    <Textarea
                      ref={textareaRef}
                      aria-label="Рассказ о задачах"
                      value={text}
                      onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => { setText(e.target.value); setError(null); }}
                      onKeyDown={handleKeyDown}
                      placeholder="Погулять с собакой, купить хлеба, позвонить маме..."
                      className="resize-none min-h-[100px] text-base"
                    />
                    <p className="text-xs text-muted-foreground">
                      Ассистент применит сразу, если это только новые задачи — иначе спросит подтверждения
                    </p>
                  </div>
                  <div
                    className={cn(
                      'col-start-1 row-start-1 flex flex-col items-center justify-center gap-2 py-6',
                      !recording.isRecording && 'invisible pointer-events-none'
                    )}
                    aria-hidden={!recording.isRecording}
                  >
                    <span className="relative flex h-8 w-8 items-center justify-center">
                      <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-destructive/30" />
                      <IconMicrophone className="relative h-5 w-5 text-destructive" />
                    </span>
                    <p className="text-sm text-muted-foreground">Идет запись…</p>
                  </div>
                </div>
                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg flex items-start gap-2">
                    <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                    <span className="min-w-0 break-words">{error}</span>
                  </div>
                )}
                {/* Полоса записи и кнопка — всегда в одной строке; кнопка держит ключ
                    "trigger" и остаётся тем же узлом DOM при старте/остановке записи,
                    иначе удержание теряло бы pointer capture на середине жеста. */}
                <div className="flex items-center gap-2">
                  {recording.isRecording && (
                    <VoiceRecordingBar key="bar" recording={recording} />
                  )}
                  {!recording.isRecording && (
                    <Button
                      key="file"
                      variant="outline"
                      aria-label="Прикрепить аудиофайл"
                      onClick={() => fileInputRef.current?.click()}
                      className="gap-2 h-11 w-11 sm:w-auto sm:px-4"
                    >
                      <IconPaperclip className="h-4 w-4" />
                      <span className="hidden sm:inline">Файл</span>
                    </Button>
                  )}
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="audio/*"
                    className="hidden"
                    onChange={handleFileSelect}
                  />
                  {!recording.isRecording && (
                    <Button
                      key="send"
                      onClick={handleSubmit}
                      disabled={!text.trim()}
                      className="flex-1 gap-2 h-11 min-w-[140px]"
                    >
                      <IconSend className="h-4 w-4" />
                      Отправить
                      <span className="hidden sm:inline text-xs opacity-60 ml-1">{shortcutLabel('Enter')}</span>
                    </Button>
                  )}
                  <VoiceRecorderTrigger
                    key="trigger"
                    recording={recording}
                    className="h-11 w-11 flex-shrink-0"
                  />
                </div>
              </motion.div>
            )}

            {phase === 'processing' && (
              <motion.div key="processing" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col items-center justify-center py-8 space-y-3">
                <motion.div animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity, ease: 'linear' }} className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full" />
                <p className="text-muted-foreground text-sm">Обрабатываю обращение…</p>
              </motion.div>
            )}

            {phase === 'confirm' && proposal && (
              <motion.div key="confirm" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                {proposal.actions.length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    Не разобрал, что нужно сделать — попробуй переформулировать.
                  </p>
                ) : proposal.actions.some((a) => a.targetTaskId) ? (
                  <p className="text-xs text-muted-foreground">
                    Здесь есть изменения к существующим задачам — проверь перед подтверждением.
                  </p>
                ) : null}

                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg flex items-start gap-2">
                    <IconAlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                    <span className="min-w-0 break-words">{error}</span>
                  </div>
                )}

                <ProposalCard
                  proposal={proposal}
                  onToggle={toggleAction}
                  onSelect={selectAction}
                  onReminderChange={changeReminder}
                  onPlannedDateChange={changePlannedDate}
                  onApply={applyCurrent}
                  onReject={rejectCurrent}
                  applying={applyProposal.isPending}
                  rejecting={rejectProposal.isPending}
                  className="w-full"
                />

                <Button variant="outline" onClick={handleBack} className="gap-2 h-11 w-full">
                  <IconArrowLeft className="h-4 w-4" />
                  Назад
                </Button>
              </motion.div>
            )}


          </AnimatePresence>
        </div>
      </DialogContent>
    </Dialog>
  );
}
