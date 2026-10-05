import { create } from 'zustand';
import { useEffect, useRef } from 'react';

interface TaskUiState {
  captureOpen: boolean;
  revealIds: string[];
  revealedAt: number;
  openCapture: () => void;
  closeCapture: () => void;
  reveal: (ids: string[]) => void;
}

export const useTaskUi = create<TaskUiState>((set) => ({
  captureOpen: false,
  revealIds: [],
  revealedAt: 0,
  openCapture: () => set({ captureOpen: true }),
  closeCapture: () => set({ captureOpen: false }),
  reveal: (ids) => set({ revealIds: ids, revealedAt: Date.now() }),
}));

export function useTaskReveal(id: string) {
  const ref = useRef<HTMLElement>(null);
  const ids = useTaskUi((s) => s.revealIds);
  const at = useTaskUi((s) => s.revealedAt);
  useEffect(() => {
    if (!ids.includes(id) || Date.now() - at > 8000 || !ref.current) return;
    const element = ref.current;
    element.classList.remove('task-reveal');
    const frame = requestAnimationFrame(() => {
      element.classList.add('task-reveal');
      if (ids[0] === id)
        element.scrollIntoView({
          block: 'nearest',
          behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth',
        });
    });
    const timer = window.setTimeout(() => element.classList.remove('task-reveal'), 3200);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(timer);
    };
  }, [id, ids, at]);
  return ref;
}

export const isActiveTask = (task: { status: string }) =>
  task.status !== 'DONE' && task.status !== 'CANCELLED';
