import type { ApplyResult, Proposal } from '@/lib/hooks/useAssistant';

export function createdTaskIds(result: ApplyResult, proposal: Proposal): string[] {
  const creates = new Set(proposal.actions.filter(action => action.type === 'CREATE').map(action => action.ordinal));
  return [...new Set(result.outcomes.filter(outcome => outcome.success && outcome.taskId && creates.has(outcome.ordinal))
    .map(outcome => outcome.taskId!))];
}
