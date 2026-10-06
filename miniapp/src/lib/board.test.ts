import { describe, expect, it } from 'vitest';
import { groupBoardTasks, isBoardStatus } from './board';
import type { TaskResponse } from './hooks/useTasks';

function task(id: string, status: string, title = id): TaskResponse {
  return { id, status, title, priority: 'MEDIUM', source: 'MANUAL', tags: [], createdAt: '', updatedAt: '' };
}

describe('доска', () => {
  it('считает все задачи большого списка и скрывает отменённые', () => {
    const tasks = Array.from({ length: 130 }, (_, index) => task(String(index), 'TODO'));
    const result = groupBoardTasks([...tasks, task('done', 'DONE'), task('cancelled', 'CANCELLED')], '');
    expect(result.TODO).toHaveLength(130);
    expect(result.DONE.map(item => item.id)).toEqual(['done']);
    expect(Object.values(result).flat()).toHaveLength(131);
  });
  it('находит по названию, описанию и группе без учёта регистра', () => {
    const tasks = [task('a', 'TODO', 'Диплом'),
      { ...task('b', 'IN_PROGRESS'), description: 'Проверить ДИПЛОМ' },
      { ...task('c', 'DONE'), groupName: 'Диплом' }, task('d', 'TODO')];
    expect(Object.values(groupBoardTasks(tasks, '  диплом  ')).flat().map(item => item.id)).toEqual(['a', 'b', 'c']);
  });
  it('показывает перенесённую старую задачу первой и сохраняет порядок остальных', () => {
    const tasks = [task('a', 'IN_PROGRESS'), task('b', 'IN_PROGRESS'), task('old', 'IN_PROGRESS')];
    const result = groupBoardTasks(tasks, '', 'old');
    expect(result.IN_PROGRESS.map(item => item.id)).toEqual(['old', 'a', 'b']);
    expect(tasks.map(item => item.id)).toEqual(['a', 'b', 'old']);
    expect(groupBoardTasks(tasks, 'a', 'old').IN_PROGRESS.map(item => item.id)).toEqual(['a']);
  });
  it('не принимает отмену или неизвестную зону как статус', () => {
    expect(isBoardStatus('board-cancel')).toBe(false);
    expect(isBoardStatus('CANCELLED')).toBe(false);
    expect(isBoardStatus('DONE')).toBe(true);
  });
});
