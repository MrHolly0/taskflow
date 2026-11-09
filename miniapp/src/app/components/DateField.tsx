import { useEffect, useState } from 'react';
import { addDays, format, isValid, parse } from 'date-fns';
import { ru } from 'date-fns/locale';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { isoToZonedDate } from '@/lib/reminderPresets';
import { DayPicker } from 'react-day-picker';
import { IconCalendar } from '@tabler/icons-react';
import { Popover, PopoverContent, PopoverTrigger } from '@/app/components/ui/popover';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/app/components/ui/dialog';
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
  const [mobile, setMobile] = useState(() => window.matchMedia('(max-width: 767px)').matches);
  useEffect(() => {
    const media = window.matchMedia('(max-width: 767px)');
    const update = () => setMobile(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
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
  const trigger = (
    <Button type="button" variant="outline" id={id} data-task-section={section}
      className="date-field" aria-label={`${label}: ${selected ? format(selected, 'd MMMM yyyy', { locale: ru }) : 'не выбрана'}`}
      disabled={disabled}>
      <IconCalendar size={18} aria-hidden />
      <span>{selected ? format(selected, 'd MMMM yyyy', { locale: ru }) : 'Выбрать дату'}</span>
    </Button>
  );
  const calendar = <>
    <div className="date-presets">
      <Button type="button" variant="secondary" onClick={() => choose(today)}>Сегодня</Button>
      <Button type="button" variant="secondary" onClick={() => choose(addDays(today, 1))}>Завтра</Button>
      <Button type="button" variant="ghost" onClick={() => choose()}>Без даты</Button>
    </div>
    <div className="date-calendar-scroll">
      <DayPicker mode="single" locale={ru} weekStartsOn={1} selected={selected}
        defaultMonth={selected ?? today} today={today} onSelect={choose} initialFocus />
    </div>
  </>;
  if (mobile) return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>{trigger}</DialogTrigger>
      <DialogContent className="date-popover date-dialog" aria-describedby={undefined}>
        <DialogHeader className="text-left pr-10"><DialogTitle>{label}</DialogTitle></DialogHeader>
        {calendar}
      </DialogContent>
    </Dialog>
  );
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent className="date-popover" align="start" collisionPadding={padding} aria-label={label}>
        {calendar}
      </PopoverContent>
    </Popover>
  );
}
