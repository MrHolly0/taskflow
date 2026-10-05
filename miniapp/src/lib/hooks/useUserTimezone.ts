import { useSettings } from './useSettings';

const FALLBACK_TIMEZONE = 'UTC';

/**
 * Пояс из настроек пользователя — источник правды для отображения дат
 * на фронтенде, а не пояс браузера/устройства. Пока настройки не
 * загрузились, isReady=false — рисовать даты в это время нельзя,
 * иначе будет видна вспышка неверного (браузерного) времени.
 */
export function useUserTimezone() {
  const { data, error, refetch } = useSettings();
  return {
    timezone: data?.timezone ?? FALLBACK_TIMEZONE,
    isReady: !!data,
    error,
    refetch,
  };
}
