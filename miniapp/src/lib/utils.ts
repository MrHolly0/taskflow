import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { Priority, Task } from './store';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDeadline(deadline: string): string {
  const date = new Date(deadline);
  const now = new Date();
  const diff = date.getTime() - now.getTime();

  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(hours / 24);

  if (hours < 0) {
    const absHours = Math.abs(hours);
    const absDays = Math.floor(absHours / 24);
    if (absDays > 0) {
      return `Просрочено на ${absDays} ${getDaysWord(absDays)}`;
    }
    return absHours > 0
      ? `Просрочено на ${absHours} ${getHoursWord(absHours)}`
      : 'Просрочено';
  }

  if (hours < 1) {
    const minutes = Math.floor(diff / (1000 * 60));
    return `через ${minutes} мин`;
  }

  if (hours < 24) {
    return `через ${hours} ${getHoursWord(hours)}`;
  }

  if (days === 1) {
    return 'Завтра';
  }

  if (days < 7) {
    return `Через ${days} ${getDaysWord(days)}`;
  }

  return date.toLocaleDateString('ru-RU', {
    day: 'numeric',
    month: 'long'
  });
}

function getHoursWord(hours: number): string {
  if (hours % 10 === 1 && hours % 100 !== 11) return 'час';
  if ([2, 3, 4].includes(hours % 10) && ![12, 13, 14].includes(hours % 100)) return 'часа';
  return 'часов';
}

function getDaysWord(days: number): string {
  if (days % 10 === 1 && days % 100 !== 11) return 'день';
  if ([2, 3, 4].includes(days % 10) && ![12, 13, 14].includes(days % 100)) return 'дня';
  return 'дней';
}

export function getPriorityColor(priority: Priority): string {
  const colors = {
    LOW: 'text-muted-foreground',
    MEDIUM: 'text-blue-500',
    HIGH: 'text-orange-500',
    URGENT: 'text-red-500',
  };
  return colors[priority];
}

export function getPriorityBgColor(priority: Priority): string {
  const colors = {
    LOW: 'border-l-4 border-l-gray-300 dark:border-l-gray-600',
    MEDIUM: 'border-l-4 border-l-blue-500',
    HIGH: 'border-l-4 border-l-orange-500',
    URGENT: 'border-l-4 border-l-red-500',
  };
  return colors[priority];
}

export function getFocusTasks(tasks: Task[]): Task[] {
  const now = new Date();

  const overdue = tasks.filter(
    (task) => task.status === 'TODO' && task.deadline && new Date(task.deadline) < now
  );

  if (overdue.length > 0) {
    return overdue.slice(0, 3);
  }

  const urgent = tasks.filter(
    (task) => task.status === 'TODO' && task.priority === 'URGENT'
  );

  if (urgent.length > 0) {
    return urgent.slice(0, 3);
  }

  const today = tasks.filter((task) => {
    if (task.status !== 'TODO' || !task.deadline) return false;
    const deadline = new Date(task.deadline);
    return deadline.toDateString() === now.toDateString();
  });

  if (today.length > 0) {
    return today.slice(0, 3);
  }

  return tasks
    .filter((task) => task.status === 'TODO')
    .sort((a, b) => {
      const priorityOrder = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
      return priorityOrder[a.priority] - priorityOrder[b.priority];
    })
    .slice(0, 3);
}
