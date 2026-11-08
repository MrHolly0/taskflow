import fs from 'node:fs';
import { getQuickJS } from 'quickjs-emscripten';

function readLine() {
  const bytes = [];
  const buffer = Buffer.alloc(1);
  while (fs.readSync(0, buffer, 0, 1, null)) {
    if (buffer[0] === 10) return Buffer.from(bytes).toString('utf8');
    bytes.push(buffer[0]);
    if (bytes.length > 65536) throw new Error('FRAME_LIMIT');
  }
  throw new Error('INPUT_CLOSED');
}
const send = value => fs.writeSync(1, JSON.stringify(value) + '\n');
let vm;
let runtime;
let program;
let parse;
try {
  const input = JSON.parse(readLine());
  if (typeof input.code !== 'string' || Buffer.byteLength(input.code) > 16384) throw new Error('CODE_LIMIT');
  const QuickJS = await getQuickJS();
  runtime = QuickJS.newRuntime();
  runtime.setMemoryLimit(16 * 1024 * 1024);
  runtime.setMaxStackSize(256 * 1024);
  const deadline = Date.now() + 1000;
  runtime.setInterruptHandler(() => Date.now() > deadline);
  vm = runtime.newContext();
  const json = vm.getProp(vm.global, 'JSON');
  parse = vm.getProp(json, 'parse');
  json.dispose();
  const fromJson = value => {
    const string = vm.newString(JSON.stringify(value));
    try { return vm.unwrapResult(vm.callFunction(parse, vm.undefined, string)); }
    finally { string.dispose(); }
  };
  let calls = 0;
  const call = vm.newFunction('__findTasks', args => {
    try {
      if (++calls > 8) throw new Error('TOOL_LIMIT');
      const selector = vm.dump(args);
      if (!selector || typeof selector !== 'object' || Array.isArray(selector)
          || JSON.stringify(selector).length > 2048) throw new Error('INVALID_SELECTOR');
      send({ type: 'call', method: 'findTasks', args: selector });
      const reply = JSON.parse(readLine());
      if (reply.error) throw new Error(String(reply.error).slice(0, 300));
      return fromJson(reply.result);
    } catch (error) {
      return { error: vm.newError(String(error.message).slice(0, 300)) };
    }
  });
  vm.setProp(vm.global, '__findTasks', call);
  call.dispose();
  const setup = vm.unwrapResult(vm.evalCode(`
    Object.defineProperty(globalThis, 'api', { value: Object.freeze({
      findTasks: __findTasks, now: ${JSON.stringify(input.now)}, tasks: ${JSON.stringify(input.tasks ?? [])}
    }), writable: false, configurable: false });
    delete globalThis.__findTasks;
  `));
  setup.dispose();
  const serialize = `
    const text = JSON.stringify(result);
    if (typeof text !== 'string' || text.length > 32768) throw new Error('RESULT_LIMIT');
    return text;`;
  const expression = input.code.trim().replace(/;+$/, '');
  let evaluated = vm.evalCode(`(async () => {
    const candidate = (${expression}\n);
    const result = await (typeof candidate === 'function' ? candidate() : candidate);
    ${serialize}
  })()`, 'plan.js');
  if (evaluated.error) {
    evaluated.error.dispose();
    evaluated = vm.evalCode(`(async () => {
      const result = await (async () => {\n${input.code}\n})();
      ${serialize}
    })()`, 'plan.js');
  }
  program = vm.unwrapResult(evaluated);
  for (let jobs = 0; jobs < 1000; jobs++) {
    const state = vm.getPromiseState(program);
    if (state.type === 'fulfilled') {
      try { send({ type: 'result', value: JSON.parse(vm.getString(state.value)), calls }); }
      finally { state.value.dispose(); }
      break;
    }
    if (state.type === 'rejected') {
      try { throw new Error(String(vm.dump(state.error)?.message ?? 'PROGRAM_ERROR')); }
      finally { state.error.dispose(); }
    }
    if (jobs === 999 || !runtime.hasPendingJob()) throw new Error('UNSETTLED_PROGRAM');
    const pending = runtime.executePendingJobs(1);
    if (pending.error) {
      try { throw new Error('PROGRAM_ERROR'); }
      finally { pending.error.dispose(); }
    }
  }
} catch (error) {
  send({ type: 'error', error: String(error.message ?? 'PROGRAM_ERROR').slice(0, 300) });
} finally {
  program?.dispose();
  parse?.dispose();
  vm?.dispose();
  runtime?.dispose();
}
