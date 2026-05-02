import { useState, useRef, useEffect, useCallback } from 'react';
import { IconMicrophone, IconSend, IconSparkles, IconCheck, IconArrowLeft, IconX } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { useCreateTask, CreateTaskRequest, useParseText, useGroups } from '@/lib/hooks/useTasks';
import { Button } from '@/app/components/ui/button';
import { Textarea } from '@/app/components/ui/textarea';
import { Input } from '@/app/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { cn } from '@/lib/utils';

type Phase = 'input' | 'recording' | 'processing' | 'confirm' | 'loading' | 'done';

interface ParsedTask {
  title: string;
  priority: 'URGENT' | 'HIGH' | 'MEDIUM' | 'LOW';
  deadline?: string;
  groupName?: string;
  estimateMinutes?: number;
}

const PRIORITIES: ParsedTask['priority'][] = ['URGENT', 'HIGH', 'MEDIUM', 'LOW'];

const PRIORITY_LABEL: Record<ParsedTask['priority'], string> = {
  URGENT: 'Срочно',
  HIGH: 'Важно',
  MEDIUM: 'Средний',
  LOW: 'Потом',
};

const PRIORITY_COLOR: Record<ParsedTask['priority'], string> = {
  URGENT: 'bg-red-100 text-red-600 dark:bg-red-950 dark:text-red-400',
  HIGH: 'bg-orange-100 text-orange-600 dark:bg-orange-950 dark:text-orange-400',
  MEDIUM: 'bg-blue-100 text-blue-600 dark:bg-blue-950 dark:text-blue-400',
  LOW: 'bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400',
};

function splitIntoTasks(text: string, groups: { name: string }[] = []): ParsedTask[] {
  const primary = /[,;]\s*|\n+|\s+ещё\s+|\s+а\s+также\s+|\s+потом\s+|\s+затем\s+/i;
  const rawSegments = text
    .split(primary)
    .map(s => s.replace(/^(мне\s+нужно|нужно|надо|следует)\s+/i, '').trim())
    .filter(s => s.length > 2);

  const segments: string[] = [];
  for (const seg of rawSegments) {
    const parts = seg.split(/\s+и\s+/i);
    if (parts.length > 1 && parts.every(p => p.trim().length >= 8)) {
      segments.push(...parts.map(p => p.trim()).filter(p => p.length > 2));
    } else {
      segments.push(seg);
    }
  }

  if (segments.length <= 1) {
    return [{ title: capitalize(text.trim()), priority: 'MEDIUM', groupName: guessGroup(text, groups) }];
  }

  return segments.map(s => ({
    title: capitalize(s),
    priority: 'MEDIUM' as const,
    groupName: guessGroup(s, groups),
  }));
}

function guessGroup(title: string | null | undefined, groups: { name: string }[]): string | undefined {
  if (!title) return undefined;
  const lower = title.toLowerCase();
  return groups.find(g => lower.includes(g.name.toLowerCase()))?.name;
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function toDateInputValue(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function toTimeInputValue(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

interface QuickInputModalProps {
  open: boolean;
  onClose: () => void;
}

export function QuickInputModal({ open, onClose }: QuickInputModalProps) {
  const { mutateAsync: createTask } = useCreateTask();
  const { mutate: parseText } = useParseText();
  const { data: groups = [] } = useGroups();

  const [phase, setPhase] = useState<Phase>('input');
  const [text, setText] = useState('');
  const [parsedTasks, setParsedTasks] = useState<ParsedTask[]>([]);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const recognitionRef = useRef<any>(null);
  const submittingRef = useRef(false);

  useEffect(() => {
    if (open) {
      setPhase('input');
      setText('');
      setParsedTasks([]);
      setError(null);
      setTimeout(() => textareaRef.current?.focus(), 100);
    }
    return () => { recognitionRef.current?.stop(); };
  }, [open]);

  const handleVoice = useCallback(() => {
    const isInTelegram = (window as any).Telegram?.WebApp?.initData;
    if (isInTelegram) {
      setError('Голосовой ввод недоступен в Telegram Mini App — введи текст вручную.');
      return;
    }

    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setError('Голосовой ввод недоступен. Используй Chrome или Safari.');
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.lang = 'ru-RU';
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;
    recognitionRef.current = recognition;

    let resultReceived = false;

    recognition.onresult = (event: any) => {
      resultReceived = true;
      setText(event.results[0][0].transcript);
      setPhase('input');
      setTimeout(() => textareaRef.current?.focus(), 100);
    };
    recognition.onerror = () => {
      setError('Не удалось распознать речь. Попробуй ещё раз.');
      setPhase('input');
    };
    recognition.onend = () => { if (!resultReceived) setPhase('input'); };

    setPhase('recording');
    recognition.start();
  }, []);

  const handleSubmit = () => {
    if (!text.trim()) return;
    setPhase('processing');
    parseText(text.trim(), {
      onSuccess: (parsed: any[]) => {
        try {
          if (parsed && parsed.length > 0) {
            setParsedTasks(parsed.map((p: any) => {
              const rawDeadline = p.deadline;
              let deadline: string | undefined;
              if (typeof rawDeadline === 'string' && rawDeadline) {
                deadline = rawDeadline.includes('T') ? rawDeadline : `${rawDeadline}T20:59:00Z`;
              } else if (typeof rawDeadline === 'number') {
                deadline = new Date(rawDeadline * 1000).toISOString();
              }
              const validPriorities = ['URGENT', 'HIGH', 'MEDIUM', 'LOW'];
              const priority = validPriorities.includes(p.priority) ? p.priority : 'MEDIUM';
              return {
                title: p.title ?? 'Без названия',
                priority: priority as ParsedTask['priority'],
                deadline,
                groupName: p.group ?? guessGroup(p.title, groups),
                estimateMinutes: p.estimateMinutes ?? undefined,
              };
            }));
          } else {
            setParsedTasks(splitIntoTasks(text.trim(), groups));
          }
        } catch {
          setParsedTasks(splitIntoTasks(text.trim(), groups));
        }
        setPhase('confirm');
      },
      onError: () => {
        setParsedTasks(splitIntoTasks(text.trim(), groups));
        setPhase('confirm');
      },
    });
  };

  const updateTask = (index: number, patch: Partial<ParsedTask>) => {
    setParsedTasks((prev: ParsedTask[]) =>
      prev.map((t: ParsedTask, i: number) => i === index ? { ...t, ...patch } : t)
    );
  };

  const cyclePriority = (index: number) => {
    const current = parsedTasks[index].priority;
    const next = PRIORITIES[(PRIORITIES.indexOf(current) + 1) % PRIORITIES.length];
    updateTask(index, { priority: next });
  };

  const handleRemoveTask = (index: number) => {
    setParsedTasks((prev: ParsedTask[]) => prev.filter((_: ParsedTask, i: number) => i !== index));
  };

  const handleConfirm = async () => {
    if (parsedTasks.length === 0 || submittingRef.current) return;
    submittingRef.current = true;
    setPhase('loading');
    try {
      for (const task of parsedTasks) {
        const dl = task.deadline || undefined;
        const deadline = dl
          ? dl.includes('T') ? dl : `${dl}T20:59:00Z`
          : undefined;
        const request: CreateTaskRequest = {
          title: task.title,
          priority: task.priority,
          deadline,
          estimateMinutes: task.estimateMinutes,
          groupName: task.groupName || undefined,
        };
        await createTask(request);
      }
      setPhase('done');
      setTimeout(() => onClose(), 1500);
    } catch (e) {
      setError(`Ошибка при создании задачи: ${(e as any)?.message ?? 'попробуй ещё раз'}`);
      setPhase('confirm');
    } finally {
      submittingRef.current = false;
    }
  };

  const handleBack = () => { setPhase('input'); setParsedTasks([]); setError(null); };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmit();
  };

  const handleClose = () => { recognitionRef.current?.stop(); onClose(); };

  return (
    <Dialog open={open} onOpenChange={(o: boolean) => !o && handleClose()}>
      <DialogContent className="sm:max-w-md w-full max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <IconSparkles className="h-5 w-5 text-primary" />
            Что записать?
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <AnimatePresence mode="wait">
            {phase === 'input' && (
              <motion.div key="input" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                <Textarea
                  ref={textareaRef}
                  value={text}
                  onChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => { setText(e.target.value); setError(null); }}
                  onKeyDown={handleKeyDown}
                  placeholder="Погулять с собакой, купить хлеба, позвонить маме..."
                  className="resize-none min-h-[100px] text-base"
                />
                <p className="text-xs text-muted-foreground">
                  Разделяй задачи запятыми — AI создаст каждую отдельно
                </p>
                {error && <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg">{error}</div>}
                <div className="flex gap-2">
                  <Button variant="outline" onClick={handleVoice} className="gap-2 h-11">
                    <IconMicrophone className="h-4 w-4" />
                    Голос
                  </Button>
                  <Button onClick={handleSubmit} disabled={!text.trim()} className="flex-1 gap-2 h-11">
                    <IconSend className="h-4 w-4" />
                    Далее
                    <span className="text-xs opacity-60 ml-1">⌘↵</span>
                  </Button>
                </div>
              </motion.div>
            )}

            {phase === 'recording' && (
              <motion.div key="recording" initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.95 }} className="flex flex-col items-center justify-center py-8 space-y-4">
                <div className="relative">
                  <motion.div animate={{ scale: [1, 1.3, 1] }} transition={{ duration: 1, repeat: Infinity }} className="absolute inset-0 bg-red-500/20 rounded-full" />
                  <div className="relative w-16 h-16 bg-red-500 rounded-full flex items-center justify-center">
                    <IconMicrophone className="h-7 w-7 text-white" />
                  </div>
                </div>
                <p className="text-muted-foreground text-sm">Говори...</p>
                <div className="flex gap-1 items-end h-6">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <motion.div key={i} className="w-1 bg-red-400 rounded-full" animate={{ height: ['8px', '20px', '8px'] }} transition={{ duration: 0.8, repeat: Infinity, delay: i * 0.1 }} />
                  ))}
                </div>
              </motion.div>
            )}

            {phase === 'processing' && (
              <motion.div key="processing" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col items-center justify-center py-8 space-y-3">
                <motion.div animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity, ease: 'linear' }} className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full" />
                <p className="text-muted-foreground text-sm">AI разбирает задачи...</p>
              </motion.div>
            )}

            {phase === 'confirm' && parsedTasks.length > 0 && (
              <motion.div key="confirm" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="space-y-3">
                <p className="text-xs text-muted-foreground">
                  {parsedTasks.length > 1
                    ? `${parsedTasks.length} задачи. Нажми на приоритет чтобы изменить, крестик — убрать.`
                    : 'Проверь и добавь.'}
                </p>

                <div className="space-y-3 max-h-[45vh] overflow-y-auto pr-1">
                  {parsedTasks.map((task: ParsedTask, i: number) => (
                    <div key={i} className="bg-muted/50 rounded-lg p-3 space-y-2.5">
                      <div className="flex items-start gap-2">
                        <div className="flex-1 min-w-0">
                          <p className="font-medium text-sm leading-snug">{task.title}</p>
                        </div>
                        <div className="flex items-center gap-1.5 flex-shrink-0">
                          <button
                            onClick={() => cyclePriority(i)}
                            title="Нажми чтобы изменить приоритет"
                            className={cn('text-xs px-2 py-0.5 rounded-full font-medium transition-all hover:opacity-80 cursor-pointer', PRIORITY_COLOR[task.priority])}
                          >
                            {PRIORITY_LABEL[task.priority]}
                          </button>
                          {parsedTasks.length > 1 && (
                            <button onClick={() => handleRemoveTask(i)} className="text-muted-foreground hover:text-destructive transition-colors">
                              <IconX className="h-3.5 w-3.5" />
                            </button>
                          )}
                        </div>
                      </div>

                      <div className="space-y-2">
                        <div className="grid grid-cols-2 gap-2">
                          <div className="space-y-1">
                            <p className="text-xs text-muted-foreground">Дедлайн</p>
                            <Input
                              type="date"
                              value={toDateInputValue(task.deadline)}
                              onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
                                const date = e.target.value;
                                const time = toTimeInputValue(task.deadline) || '20:59';
                                updateTask(i, { deadline: date ? new Date(`${date}T${time}`).toISOString() : undefined });
                              }}
                              className="h-8 text-xs"
                            />
                          </div>
                          <div className="space-y-1">
                            <p className="text-xs text-muted-foreground">Время</p>
                            <Input
                              type="time"
                              value={toTimeInputValue(task.deadline)}
                              onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
                                const time = e.target.value;
                                const date = toDateInputValue(task.deadline);
                                if (date) updateTask(i, { deadline: new Date(`${date}T${time}`).toISOString() });
                              }}
                              disabled={!task.deadline}
                              className="h-8 text-xs"
                            />
                          </div>
                        </div>
                        {groups.length > 0 && (
                          <div className="space-y-1">
                            <p className="text-xs text-muted-foreground">Группа</p>
                            <Select
                              value={task.groupName ?? '__none__'}
                              onValueChange={(v: string) => updateTask(i, { groupName: v === '__none__' ? undefined : v })}
                            >
                              <SelectTrigger className="h-8 text-xs">
                                <SelectValue placeholder="—" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="__none__">—</SelectItem>
                                {groups.map(g => (
                                  <SelectItem key={g.id} value={g.name}>{g.name}</SelectItem>
                                ))}
                              </SelectContent>
                            </Select>
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>

                {error && <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg">{error}</div>}

                <div className="flex gap-2">
                  <Button variant="outline" onClick={handleBack} className="gap-2 h-11 flex-1">
                    <IconArrowLeft className="h-4 w-4" />
                    Назад
                  </Button>
                  <Button onClick={handleConfirm} className="flex-1 gap-2 h-11">
                    <IconCheck className="h-4 w-4" />
                    Добавить{parsedTasks.length > 1 ? ` (${parsedTasks.length})` : ''}
                  </Button>
                </div>
              </motion.div>
            )}

            {phase === 'loading' && (
              <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="flex flex-col items-center justify-center py-8 space-y-3">
                <motion.div animate={{ rotate: 360 }} transition={{ duration: 1, repeat: Infinity, ease: 'linear' }} className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full" />
                <p className="text-muted-foreground text-sm">Добавляем задачи...</p>
              </motion.div>
            )}

            {phase === 'done' && (
              <motion.div key="done" initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} className="flex flex-col items-center justify-center py-6 space-y-4">
                <motion.div initial={{ scale: 0 }} animate={{ scale: 1 }} transition={{ type: 'spring', duration: 0.5 }} className="w-14 h-14 bg-green-500 rounded-full flex items-center justify-center">
                  <IconCheck className="h-7 w-7 text-white" />
                </motion.div>
                <p className="font-medium">
                  {parsedTasks.length > 1 ? `Добавлено ${parsedTasks.length} задачи!` : 'Добавлено!'}
                </p>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </DialogContent>
    </Dialog>
  );
}
