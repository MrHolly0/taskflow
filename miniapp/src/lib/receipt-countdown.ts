export function receiptCountdown(duration: number, onElapsed: () => void) {
  let remaining = duration;
  let started = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let disposed = false;
  return {
    resume() {
      if (disposed || timer !== undefined) return;
      started = Date.now();
      timer = setTimeout(() => { timer = undefined; disposed = true; onElapsed(); }, Math.max(0, remaining));
    },
    pause() {
      if (timer === undefined) return;
      clearTimeout(timer);
      timer = undefined;
      remaining -= Date.now() - started;
    },
    dispose() { disposed = true; clearTimeout(timer); timer = undefined; },
  };
}
