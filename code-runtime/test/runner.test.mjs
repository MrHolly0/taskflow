import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';

function execute(code, respond = () => []) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['--max-old-space-size=64', 'runner.mjs'], { cwd: new URL('..', import.meta.url) });
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('runner hung')); }, 4000);
    let final;
    const lines = createInterface({ input: child.stdout });
    lines.on('line', line => {
      const frame = JSON.parse(line);
      if (frame.type === 'call') child.stdin.write(JSON.stringify({ result: respond(frame.args) }) + '\n');
      else final = frame;
    });
    child.on('error', reject);
    child.on('exit', () => { clearTimeout(timer); resolve(final); });
    child.stdin.write(JSON.stringify({ code, now: '2026-11-08T10:00:00+03:00' }) + '\n');
  });
}
test('calls only the injected tool and returns a compact plan', async () => {
  const result = await execute('const tasks = await api.findTasks({query:"диплом"}); return {actions: tasks.map(t => ({type:"complete",task_ref:t.ref}))};',
    () => [{ ref: 'T1', title: 'Диплом' }]);
  assert.equal(result.type, 'result');
  assert.equal(result.calls, 1);
  assert.deepEqual(result.value.actions, [{ type: 'complete', task_ref: 'T1' }]);
});
test('does not expose Node, filesystem, network or dynamic imports', async () => {
  for (const code of ['return process.env;', 'return require("node:fs");', 'return await fetch("https://example.com");', 'return await import("node:fs");', 'return api.findTasks.constructor("return process")();']) {
    assert.equal((await execute(code)).type, 'error');
  }
});
test('interrupts an infinite loop and handles memory exhaustion', async () => {
  assert.equal((await execute('while(true){}')).type, 'error');
  assert.equal((await execute('return "x".repeat(100000000);')).type, 'error');
});
test('bounds tool calls, output, code and unresolved promises', async () => {
  for (const code of ['for(let i=0;i<9;i++) await api.findTasks({}); return {};', 'return "x".repeat(40000);', 'return await new Promise(()=>{});', 'x'.repeat(17000)]) {
    assert.equal((await execute(code)).type, 'error');
  }
});

test('accepts full async functions and executes each program once', async () => {
  for (const source of [
    "async () => { const found = await api.findTasks({group:'Учёба',overdue:true,all:true}); return {actions: found.map(t => ({type:'reschedule',task_ref:t.ref,new_deadline:'2030-11-15T18:00:00+03:00'}))}; }",
    "async function () { const found = await api.findTasks({query:'деканат'}); return {actions: [{type:'complete',task_ref:found[0].ref}]}; };"
  ]) {
    let calls = 0;
    const result = await execute(source, () => { calls++; return [{ref:'T1',title:'Документы'}]; });
    assert.equal(result.type, 'result');
    assert.equal(result.value.actions.length, 1);
    assert.equal(calls, 1);
  }
});
