import { useEffect, useState } from 'react';
import { addDays, format, isValid, parse } from 'date-fns';
import { ru } from 'date-fns/locale';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { isoToZonedDate } from '@/lib/reminderPresets';
import { DayPicker } from 'react-day-picker';
import { IconCalendar } from '@tabler/icons-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/app/components/ui/popover';
import { Button } from '@/app/components/ui/button';
import 'react-day-picker/dist/style.css';

export function DateField({ value, onChange, label, id, section, disabled = false }: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  id?: string;
  section?: string;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const { timezone } = useUserTimezone();
  const today = parse(isoToZonedDate(new Date().toISOString(), timezone), 'yyyy-MM-dd', new Date());
  const [padding, setPadding] = useState({ top: 12, bottom: 12, left: 12, right: 12 });
  useEffect(() => {
    if (!open) return;
    const update = () => {
      const style = getComputedStyle(document.documentElement);
      const safe = (side: string) => parseFloat(style.getPropertyValue(`--safe-${side}`)) || 0;
      const next = { top: Math.max(0, safe('top') - (window.visualViewport?.offsetTop ?? 0)) + 12,
        bottom: safe('bottom') + 12, left: safe('left') + 12, right: safe('right') + 12 };
      setPadding(previous => Object.keys(next).every(key => previous[key as keyof typeof next] === next[key as keyof typeof next]) ? previous : next);
    };
    update();
    window.visualViewport?.addEventListener('resize', update);
    window.visualViewport?.addEventListener('scroll', update);
    const observer = new MutationObserver(update);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['style'] });
    return () => {
      observer.disconnect();
      window.visualViewport?.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('scroll', update);
    };
  }, [open]);
  const parsed = value ? parse(value, 'yyyy-MM-dd', new Date()) : undefined;
  const selected = parsed && isValid(parsed) ? parsed : undefined;
  const choose = (date?: Date) => {
    onChange(date ? format(date, 'yyyy-MM-dd') : '');
    setOpen(false);
  };
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" id={id} data-task-section={section}
          className="date-field" aria-label={`${label}: ${selected ? format(selected, 'd MMMM yyyy', { locale: ru }) : 'не выбрана'}`}
          disabled={disabled}>
          <IconCalendar size={18} aria-hidden />
          <span>{selected ? format(selected, 'd MMMM yyyy', { locale: ru }) : 'Выбрать дату'}</span>
        </Button>
      </PopoverTrigger>
      <PopoverContent className="date-popover" align="start" collisionPadding={padding} aria-label={label}>
        <div className="date-presets">
          <Button type="button" variant="secondary" onClick={() => choose(today)}>Сегодня</Button>
          <Button type="button" variant="secondary" onClick={() => choose(addDays(today, 1))}>Завтра</Button>
          <Button type="button" variant="ghost" onClick={() => choose()}>Без даты</Button>
        </div>
        <DayPicker mode="single" locale={ru} weekStartsOn={1} selected={selected}
          defaultMonth={selected ?? today} today={today} onSelect={choose} initialFocus />
      </PopoverContent>
    </Popover>
  );
}
