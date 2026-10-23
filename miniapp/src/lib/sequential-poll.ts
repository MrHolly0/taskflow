export function sequentialPoll<T>(request: () => Promise<T>, receive: (result: T) => boolean,
  delay: (failures: number, error?: unknown) => number) {
  let stopped = false;
  let failures = 0;
  let timer: ReturnType<typeof setTimeout>;
  const tick = async () => {
    let error: unknown;
    try {
      const result = await request();
      failures = 0;
      if (stopped || !receive(result)) { stopped = true; return; }
    } catch (failure) {
      error = failure;
      failures++;
    }
    if (!stopped) timer = setTimeout(tick, delay(failures, error));
  };
  timer = setTimeout(tick, delay(0));
  return () => { stopped = true; clearTimeout(timer); };
}
