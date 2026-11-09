import { describe, expect, it } from 'vitest';
import { buildAssistantRecommendations } from './assistant-recommendations';
import type { TaskResponse } from './hooks/useTasks';
const now = new Date('2026-11-10T12:00:00Z');
const task = (id: string, patch: Partial<TaskResponse> = {}): TaskResponse => ({ id, title: id, status: 'TODO', priority: 'MEDIUM', source: 'MANUAL', tags: [], createdAt: now.toISOString(), updatedAt: now.toISOString(), ...patch });
describe('рекомендации по текущим задачам', () => {
  it('не придумывает рекомендации при пустом или завершённом списке', () => {
    expect(buildAssistantRecommendations([], true, now)).toEqual([]);
    expect(buildAssistantRecommendations([task('done', { status: 'DONE' }), task('cancel', { status: 'CANCELLED' })], true, now)).toEqual([]);
  });
  it('предлагает начать единственную будущую задачу без выдуманной срочности', () => {
    const [item] = buildAssistantRecommendations([task('курсовой', { deadline: '2026-11-17T12:00:00Z' })], true, now);
    expect(item.task.title).toBe('курсовой');expect(item.kind).toBe('start');
  });
  it('предлагает уточнить просроченный срок, не завершает дело за пользователя', () => {
    expect(buildAssistantRecommendations([task('late', { deadline: '2026-11-09T12:00:00Z' })], false, now)[0].kind).toBe('deadline');
  });
  it('предлагает продолжить начатое вместо запуска ещё одного дела', () => {
    const items = buildAssistantRecommendations([task('todo'), task('working', { status: 'IN_PROGRESS' })], false, now);
    expect(items[0].kind).toBe('continue');expect(items.some(item => item.kind === 'start')).toBe(false);
  });
  it('не заявляет об отсутствии напоминаний, если они не загружены', () => {
    const items = buildAssistantRecommendations([task('working', { status: 'IN_PROGRESS' }), task('unknown', { deadline: '2026-11-17T12:00:00Z' })], false, now);
    expect(items.some(item => item.kind === 'reminder')).toBe(false);
  });
  it('учитывает действующее напоминание и не предлагает группировку без групп', () => {
    const items = buildAssistantRecommendations([task('working', { status: 'IN_PROGRESS' }), task('ready', { deadline: '2026-11-17T12:00:00Z', reminders: [{ id: 'r', fireAt: '2026-11-16T12:00:00Z', status: 'PENDING', persistent: false }] })], false, now);
    expect(items.map(item => item.kind)).toEqual(['continue']);
  });
  it('не предлагает начать явно отложенное дело', () => {
    const items = buildAssistantRecommendations([task('later', { plannedDate: '2026-11-25T09:00:00Z' })], true, now);
    expect(items.map(item => item.kind)).toEqual(['planned']);
  });
  it('сохраняет план текущего дня в другом часовом поясе', () => {
    const items = buildAssistantRecommendations([task('today', { plannedDate: '2026-11-10T09:00:00+09:00' })], false, now, 'Asia/Tokyo');
    expect(items[0].kind).toBe('start');
  });
});
