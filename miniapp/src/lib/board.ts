import type { TaskResponse } from '@/lib/hooks/useTasks';

export const BOARD_STATUSES = ['TODO', 'IN_PROGRESS', 'DONE'] as const;
export type BoardStatus = (typeof BOARD_STATUSES)[number];
export type BoardTasks = Record<BoardStatus, TaskResponse[]>;

export function isBoardStatus(status: string): status is BoardStatus {
  return BOARD_STATUSES.some(value => value === status);
}

export function groupBoardTasks(tasks: TaskResponse[], search: string, leadingId?: string) {
  const query = search.trim().toLocaleLowerCase('ru');
  const result: BoardTasks = { TODO: [], IN_PROGRESS: [], DONE: [] };
  for (const task of tasks) {
    if (!isBoardStatus(task.status)) continue;
    if (query && !`${task.title} ${task.description ?? ''} ${task.groupName ?? ''}`
      .toLocaleLowerCase('ru').includes(query)) continue;
    if (task.id === leadingId) result[task.status].unshift(task);
    else result[task.status].push(task);
  }
  return result;
}
