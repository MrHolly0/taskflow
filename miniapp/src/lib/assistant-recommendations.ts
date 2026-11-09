import type { TaskResponse } from '@/lib/hooks/useTasks';
import { zonedDayKey } from '@/lib/utils';

export type RecommendationKind = 'deadline' | 'continue' | 'start' | 'reminder' | 'group' | 'planned';
export interface AssistantRecommendation {
  task: TaskResponse;
  kind: RecommendationKind;
  reason: string;
  label: string;
}

export function buildAssistantRecommendations(tasks: TaskResponse[], hasGroups: boolean, now: Date, timezone = 'UTC'): AssistantRecommendation[] {
  const all = tasks.filter(task => task.status === 'TODO' || task.status === 'IN_PROGRESS');
  const today = zonedDayKey(now, timezone);
  const active = all.filter(task => task.status === 'IN_PROGRESS' || !task.plannedDate || zonedDayKey(task.plannedDate, timezone) <= today);
  const suggestions: AssistantRecommendation[] = [];
  const add = (task: TaskResponse, kind: RecommendationKind, reason: string, label: string) => {
    if (!suggestions.some(item => item.task.id === task.id)) suggestions.push({ task, kind, reason, label });
  };
  for (const task of active) {
    if (task.deadline && Date.parse(task.deadline) < now.getTime())
      add(task, 'deadline', 'Срок уже прошёл. Проверь, актуален ли он.', 'Уточнить срок');
  }
  const working = active.find(task => task.status === 'IN_PROGRESS');
  if (working) add(working, 'continue', 'Ты уже взял это дело в работу.', 'Продолжить');
  else {
    const next = active.find(task => task.status === 'TODO');
    if (next) add(next, 'start', 'Ещё не начато и не отложено на другой день.', 'Взять в работу');
  }
  for (const task of active) {
    if (task.deadline && Date.parse(task.deadline) > now.getTime() && task.reminders !== undefined
      && !task.reminders.some(reminder => reminder.status === 'PENDING' && Date.parse(reminder.fireAt) > now.getTime()))
      add(task, 'reminder', 'Есть срок, но нет следующего напоминания.', 'Назначить напоминание');
  }
  if (hasGroups) for (const task of active) {
    if (!task.groupId) add(task, 'group', 'Задача пока без группы.', 'Выбрать группу');
  }
  if (!suggestions.length && all[0]) add(all[0], 'planned', 'День работы уже выбран. Можно посмотреть свой план.', 'Посмотреть план');
  return suggestions;
}
