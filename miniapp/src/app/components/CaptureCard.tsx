import { useEffect, useRef, useState } from 'react';
import {
  IconMicrophone,
  IconArrowRight,
  IconPlayerPauseFilled,
  IconPlayerPlay,
} from '@tabler/icons-react';
import { useTaskUi } from '@/lib/task-ui';

export function SoundWave({ playing = true }: { playing?: boolean }) {
  const ref = useRef<HTMLSpanElement>(null);
  useEffect(() => {
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    let frame = 0;
    const paint = (time: number) => {
      const bars = ref.current?.children;
      if (!bars) return;
      for (let i = 0; i < bars.length; i++) {
        const phase = i * 0.44 - (playing && !reduced.matches ? time * 0.004 : 0);
        const envelope = 0.2 + 0.8 * Math.pow(Math.sin(i * 0.19), 2);
        (bars[i] as HTMLElement).style.transform =
          `scaleY(${0.15 + envelope * (0.18 + 0.67 * Math.abs(Math.sin(phase)))})`;
      }
      if (playing && !reduced.matches) frame = requestAnimationFrame(paint);
    };
    paint(0);
    const change = () => {
      cancelAnimationFrame(frame);
      paint(0);
    };
    reduced.addEventListener('change', change);
    return () => {
      cancelAnimationFrame(frame);
      reduced.removeEventListener('change', change);
    };
  }, [playing]);
  return (
    <span ref={ref} className="sound-wave" aria-hidden="true">
      {Array.from({ length: 34 }, (_, i) => (
        <i key={i} />
      ))}
    </span>
  );
}

export function CaptureCard() {
  const open = useTaskUi((s) => s.openCapture);
  const [playing, setPlaying] = useState(true);
  return (
    <aside className="capture-card">
      <p className="capture-eyebrow">
        <IconMicrophone />
        Расскажи Мунину
      </p>
      <h2>Расскажи, что нужно сделать.</h2>
      <p className="capture-example">
        <span>Макет отправил, Саше позвонил.</span>
        <br />
        Ещё надо записаться к стоматологу.
      </p>
      <button
        className="wave-player"
        aria-label={playing ? 'Приостановить волну' : 'Продолжить волну'}
        onClick={() => setPlaying(!playing)}
      >
        <SoundWave playing={playing} />
        {playing ? <IconPlayerPauseFilled aria-hidden="true" /> : <IconPlayerPlay aria-hidden="true" />}
      </button>
      <button className="capture-card-action" onClick={open}>
        Текст или голос
        <IconArrowRight />
      </button>
      <small>Расскажи словами, задачи появятся сами.</small>
    </aside>
  );
}
