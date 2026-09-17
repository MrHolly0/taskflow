import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { toZonedTime, fromZonedTime } from 'date-fns-tz';
import { isSameDay, addDays, format, differenceInCalendarDays } from 'date-fns';
import { Priority } from './store';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Тот же календарный день в указанном поясе — не в поясе браузера. */
export function isSameZonedDay(a: Date | string, b: Date | string, timezone: string): boolean {
  return isSameDay(toZonedTime(a, timezone), toZonedTime(b, timezone));
}

/** Ключ календарного дня (yyyy-MM-dd) в указанном поясе — для группировки/подсчёта уникальных дней. */
export function zonedDayKey(date: Date | string, timezone: string): string {
  return format(toZonedTime(date, timezone), 'yyyy-MM-dd');
}

/**
 * Конец календарного дня (23:59:59), отстоящего на daysFromNow дней от `now`,
 * посчитанный в указанном поясе, возвращённый как настоящий момент времени (UTC-инстант).
 */
export function endOfZonedDay(daysFromNow: number, timezone: string, now: Date = new Date()): Date {
  const zonedNow = toZonedTime(now, timezone);
  const zonedTarget = addDays(zonedNow, daysFromNow);
  const wallClockEnd = new Date(
    zonedTarget.getFullYear(),
    zonedTarget.getMonth(),
    zonedTarget.getDate(),
    23, 59, 59
  );
  return fromZonedTime(wallClockEnd, timezone);
}

/**
 * Для выполненной или отменённой задачи срок — уже не действующее
 * обязательство, поэтому относительные подписи («Просрочено», «Завтра»)
 * не подходят: только нейтральная дата.
 */
export function formatDeadline(deadline: string, timezone: string, status: string): string {
  const date = new Date(deadline);

  if (status === 'DONE' || status === 'CANCELLED') {
    return date.toLocaleDateString('ru-RU', {
      day: 'numeric',
      month: 'long',
      timeZone: timezone,
    });
  }

  const now = new Date();
  const diff = date.getTime() - now.getTime();
  const hours = Math.floor(diff / (1000 * 60 * 60));
  const dayDiff = differenceInCalendarDays(
    toZonedTime(date, timezone),
    toZonedTime(now, timezone)
  );

  if (diff < 0) {
    if (dayDiff < 0) {
      const absDays = Math.abs(dayDiff);
      return `Просрочено на ${absDays} ${getDaysWord(absDays)}`;
    }

    const absHours = Math.abs(hours);
    return absHours > 0
      ? `Просрочено на ${absHours} ${getHoursWord(absHours)}`
      : 'Просрочено';
  }

  if (dayDiff === 1) {
    return 'Завтра';
  }

  if (dayDiff === 2) {
    return 'Послезавтра';
  }

  if (dayDiff > 2 && dayDiff < 7) {
    return `Через ${dayDiff} ${getDaysWord(dayDiff)}`;
  }

  if (hours < 1) {
    const minutes = Math.floor(diff / (1000 * 60));
    return `через ${minutes} мин`;
  }

  if (hours < 24) {
    return `через ${hours} ${getHoursWord(hours)}`;
  }

  return date.toLocaleDateString('ru-RU', {
    day: 'numeric',
    month: 'long',
    timeZone: timezone,
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

/** Короткая метка времени напоминания в поясе пользователя — общая для карточки задачи и значка в списке/на доске. */
export function formatReminderTime(fireAt: string, timezone: string): string {
  const zoned = toZonedTime(fireAt, timezone);
  const zonedNow = toZonedTime(new Date(), timezone);
  const time = `${String(zoned.getHours()).padStart(2, '0')}:${String(zoned.getMinutes()).padStart(2, '0')}`;
  const dayDiff = differenceInCalendarDays(zoned, zonedNow);

  if (dayDiff === 0) return `сегодня, ${time}`;
  if (dayDiff === 1) return `завтра, ${time}`;
  return `${zoned.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })}, ${time}`;
}

/** День исполнения — без времени, в отличие от formatReminderTime: время внутри дня здесь не имеет смысла для пользователя. */
export function formatPlannedDate(plannedDate: string, timezone: string): string {
  const zoned = toZonedTime(plannedDate, timezone);
  const zonedNow = toZonedTime(new Date(), timezone);
  const dayDiff = differenceInCalendarDays(zoned, zonedNow);

  if (dayDiff === 0) return 'сегодня';
  if (dayDiff === 1) return 'завтра';
  return zoned.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
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
