import { useState, useRef, useEffect } from 'react';
import { IconMicrophone, IconSend, IconSparkles, IconCheck, IconArrowLeft } from '@tabler/icons-react';
import { motion, AnimatePresence } from 'motion/react';
import { useCreateTask, CreateTaskRequest } from '@/lib/hooks/useTasks';
import { Button } from '@/app/components/ui/button';
import { Textarea } from '@/app/components/ui/textarea';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import { cn } from '@/lib/utils';

type Phase = 'input' | 'recording' | 'processing' | 'confirm' | 'loading' | 'done';

interface ParsedTask {
  title: string;
  priority: 'URGENT' | 'HIGH' | 'MEDIUM' | 'LOW';
  groupId?: string;
  estimateMinutes?: number;
}

function parseMockTask(text: string): ParsedTask {
  const lower = text.toLowerCase();
  let priority: ParsedTask['priority'] = 'MEDIUM';
  if (lower.includes('срочно') || lower.includes('важно') || lower.includes('deadline')) {
    priority = 'URGENT';
  } else if (lower.includes('сегодня') || lower.includes('скоро')) {
    priority = 'HIGH';
  } else if (lower.includes('когда-нибудь') || lower.includes('потом')) {
    priority = 'LOW';
  }

  let estimateMinutes: number | undefined;
  const timeMatch = lower.match(/(\d+)\s*(мин|час)/);
  if (timeMatch) {
    const val = parseInt(timeMatch[1]);
    estimateMinutes = timeMatch[2] === 'час' ? val * 60 : val;
  }

  const title = text.charAt(0).toUpperCase() + text.slice(1).replace(/[.!?]+$/, '');

  return { title, priority, estimateMinutes };
}

const MOCK_VOICE_PHRASES = [
  'Купить хлеб и молоко по пути домой',
  'Написать письмо Ивану по поводу встречи',
  'Сделать зарядку утром, 20 минут',
  'Позвонить в банк насчёт карточки',
];

const PRIORITY_LABEL: Record<ParsedTask['priority'], string> = {
  URGENT: 'Срочно',
  HIGH: 'Важно',
  MEDIUM: 'Средний',
  LOW: 'Когда будет время',
};

const PRIORITY_COLOR: Record<ParsedTask['priority'], string> = {
  URGENT: 'text-red-500',
  HIGH: 'text-orange-500',
  MEDIUM: 'text-blue-500',
  LOW: 'text-gray-400',
};

interface QuickInputModalProps {
  open: boolean;
  onClose: () => void;
}

export function QuickInputModal({ open, onClose }: QuickInputModalProps) {
  const { mutate: createTask, isPending: isCreating } = useCreateTask();
  const [phase, setPhase] = useState<Phase>('input');
  const [text, setText] = useState('');
  const [parsedTask, setParsedTask] = useState<ParsedTask | null>(null);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const recordingTimerRef = useRef<NodeJS.Timeout>();
  const processingTimerRef = useRef<NodeJS.Timeout>();

  useEffect(() => {
    if (open) {
      setPhase('input');
      setText('');
      setParsedTask(null);
      setError(null);
      setTimeout(() => textareaRef.current?.focus(), 100);
    }
    return () => {
      clearTimeout(recordingTimerRef.current);
      clearTimeout(processingTimerRef.current);
    };
  }, [open]);

  const handleVoice = () => {
    setPhase('recording');
    recordingTimerRef.current = setTimeout(() => {
      const randomPhrase = MOCK_VOICE_PHRASES[Math.floor(Math.random() * MOCK_VOICE_PHRASES.length)];
      setText(randomPhrase);
      setPhase('input');
      setTimeout(() => textareaRef.current?.focus(), 100);
    }, 2000);
  };

  const handleSubmit = () => {
    if (!text.trim()) return;
    setPhase('processing');
    processingTimerRef.current = setTimeout(() => {
      const parsed = parseMockTask(text.trim());
      setParsedTask(parsed);
      setPhase('confirm');
    }, 1200);
  };

  const handleConfirm = () => {
    if (!parsedTask) return;
    setPhase('loading');
    const request: CreateTaskRequest = {
      title: parsedTask.title,
      priority: parsedTask.priority,
      estimateMinutes: parsedTask.estimateMinutes,
    };
    createTask(request, {
      onSuccess: () => {
        setPhase('done');
        setTimeout(() => {
          onClose();
        }, 1500);
      },
      onError: (err) => {
        setError('Ошибка при создании задачи');
        setPhase('confirm');
      },
    });
  };

  const handleBack = () => {
    setPhase('input');
    setParsedTask(null);
    setError(null);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      handleSubmit();
    }
  };

  const handleClose = () => {
    clearTimeout(recordingTimerRef.current);
    clearTimeout(processingTimerRef.current);
    onClose();
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && handleClose()}>
      <DialogContent className="sm:max-w-md w-full">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <IconSparkles className="h-5 w-5 text-primary" />
            Что записать?
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <AnimatePresence mode="wait">
            {phase === 'input' && (
              <motion.div
                key="input"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                className="space-y-3"
              >
                <Textarea
                  ref={textareaRef}
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Напиши задачу как есть — AI сам разберёт приоритет и время..."
                  className="resize-none min-h-[100px] text-base"
                />
                <p className="text-xs text-muted-foreground">
                  Например: «Срочно написать отчёт для Димы, 2 часа»
                </p>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    onClick={handleVoice}
                    className="gap-2 h-11"
                  >
                    <IconMicrophone className="h-4 w-4" />
                    Голос
                  </Button>
                  <Button
                    onClick={handleSubmit}
                    disabled={!text.trim()}
                    className="flex-1 gap-2 h-11"
                  >
                    <IconSend className="h-4 w-4" />
                    Далее
                    <span className="text-xs opacity-60 ml-1">⌘↵</span>
                  </Button>
                </div>
              </motion.div>
            )}

            {phase === 'recording' && (
              <motion.div
                key="recording"
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.95 }}
                className="flex flex-col items-center justify-center py-8 space-y-4"
              >
                <div className="relative">
                  <motion.div
                    animate={{ scale: [1, 1.3, 1] }}
                    transition={{ duration: 1, repeat: Infinity }}
                    className="absolute inset-0 bg-red-500/20 rounded-full"
                  />
                  <div className="relative w-16 h-16 bg-red-500 rounded-full flex items-center justify-center">
                    <IconMicrophone className="h-7 w-7 text-white" />
                  </div>
                </div>
                <p className="text-muted-foreground text-sm">Говори...</p>
                <div className="flex gap-1 items-end h-6">
                  {[1, 2, 3, 4, 5].map((i) => (
                    <motion.div
                      key={i}
                      className="w-1 bg-red-400 rounded-full"
                      animate={{ height: ['8px', '20px', '8px'] }}
                      transition={{
                        duration: 0.8,
                        repeat: Infinity,
                        delay: i * 0.1,
                      }}
                    />
                  ))}
                </div>
              </motion.div>
            )}

            {phase === 'processing' && (
              <motion.div
                key="processing"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                className="flex flex-col items-center justify-center py-8 space-y-3"
              >
                <motion.div
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
                  className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full"
                />
                <p className="text-muted-foreground text-sm">AI разбирает задачу...</p>
              </motion.div>
            )}

            {phase === 'confirm' && parsedTask && (
              <motion.div
                key="confirm"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                className="space-y-4"
              >
                <div className="space-y-3 bg-muted/50 p-4 rounded-lg">
                  <div>
                    <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">
                      Задача
                    </p>
                    <p className="font-medium text-base">{parsedTask.title}</p>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">
                        Приоритет
                      </p>
                      <p className={cn('font-medium text-sm', PRIORITY_COLOR[parsedTask.priority])}>
                        {PRIORITY_LABEL[parsedTask.priority]}
                      </p>
                    </div>
                    {parsedTask.estimateMinutes && (
                      <div>
                        <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">
                          Время
                        </p>
                        <p className="font-medium text-sm">
                          ~{parsedTask.estimateMinutes} мин
                        </p>
                      </div>
                    )}
                  </div>
                </div>

                {error && (
                  <div className="text-sm text-destructive bg-destructive/10 p-3 rounded-lg">
                    {error}
                  </div>
                )}

                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    onClick={handleBack}
                    className="gap-2 h-11 flex-1"
                  >
                    <IconArrowLeft className="h-4 w-4" />
                    Назад
                  </Button>
                  <Button
                    onClick={handleConfirm}
                    disabled={isCreating}
                    className="flex-1 gap-2 h-11"
                  >
                    <IconCheck className="h-4 w-4" />
                    Добавить
                  </Button>
                </div>
              </motion.div>
            )}

            {phase === 'loading' && (
              <motion.div
                key="loading"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                className="flex flex-col items-center justify-center py-8 space-y-3"
              >
                <motion.div
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
                  className="w-10 h-10 border-2 border-primary/20 border-t-primary rounded-full"
                />
                <p className="text-muted-foreground text-sm">Добавляем задачу...</p>
              </motion.div>
            )}

            {phase === 'done' && parsedTask && (
              <motion.div
                key="done"
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                className="flex flex-col items-center justify-center py-6 space-y-4"
              >
                <motion.div
                  initial={{ scale: 0 }}
                  animate={{ scale: 1 }}
                  transition={{ type: 'spring', duration: 0.5 }}
                  className="w-14 h-14 bg-green-500 rounded-full flex items-center justify-center"
                >
                  <IconCheck className="h-7 w-7 text-white" />
                </motion.div>
                <div className="text-center space-y-1">
                  <p className="font-medium">Добавлено!</p>
                  <p className="text-sm text-muted-foreground">«{parsedTask.title}»</p>
                </div>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </DialogContent>
    </Dialog>
  );
}
