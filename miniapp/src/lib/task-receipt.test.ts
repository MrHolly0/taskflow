import { describe, expect, it } from 'vitest';
import { createdTaskIds } from './task-receipt';
import type { ApplyResult, Proposal } from './hooks/useAssistant';

describe('отмена создания из результата', () => {
  it('предлагается только для успешно созданных задач, без повторов и чужих изменений', () => {
    const proposal = { actions: [{ ordinal: 1, type: 'CREATE' }, { ordinal: 2, type: 'COMPLETE' }, { ordinal: 3, type: 'CREATE' }, { ordinal: 4, type: 'CREATE' }] } as Proposal;
    const result = { outcomes: [
      { ordinal: 1, taskId: 'new-task', success: true },
      { ordinal: 2, taskId: 'existing-task', success: true },
      { ordinal: 3, taskId: 'failed-task', success: false },
      { ordinal: 4, taskId: 'new-task', success: true },
    ] } as ApplyResult;
    expect(createdTaskIds(result, proposal)).toEqual(['new-task']);
  });
});
