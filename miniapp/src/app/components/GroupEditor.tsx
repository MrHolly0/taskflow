import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { toast } from 'sonner';
import { type GroupResponse, useCreateGroup, useUpdateGroup } from '@/lib/hooks/useTasks';
import { GROUP_ICON_KEYS, getGroupIcon } from '@/lib/group-icons';
import { Input } from '@/app/components/ui/input';
import { Button } from '@/app/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '@/app/components/ui/dialog';

export const GROUP_COLORS: Record<string, string> = {
  blue: '#4a7ac1',
  purple: '#9465b5',
  green: '#4d9364',
  orange: '#bf7b3e',
  red: '#b95e7d',
  yellow: '#b89330',
  pink: '#bd679a',
  cyan: '#59979b',
};
const ICON_LABELS: Record<string, string> = {
  briefcase: 'Работа',
  school: 'Учёба',
  users: 'Личное',
  shopping: 'Покупки',
  run: 'Спорт',
  cash: 'Финансы',
  game: 'Игры',
  book: 'Чтение',
  palette: 'Идеи',
  plant: 'Растения',
  home: 'Дом',
  plane: 'Поездки',
  music: 'Музыка',
  pill: 'Здоровье',
  paw: 'Животные',
  folder: 'Папка',
};
const COLORS_LABELS: Record<string, string> = {
  blue: 'Синий',
  purple: 'Фиолетовый',
  green: 'Зелёный',
  orange: 'Оранжевый',
  red: 'Красный',
  yellow: 'Жёлтый',
  pink: 'Розовый',
  cyan: 'Бирюзовый',
};
const nameSchema = z.string().trim().min(1, 'Напиши название').max(128, 'Не больше 128 символов');
export function GroupEmblem({ group }: { group: Pick<GroupResponse, 'icon' | 'color'> }) {
  const Icon = getGroupIcon(group.icon || 'folder');
  return (
    <span
      className="group-emblem"
      style={{ color: GROUP_COLORS[group.color || 'blue'] || GROUP_COLORS.blue }}
    >
      <Icon aria-hidden="true" />
    </span>
  );
}
export function GroupEditor({
  open,
  group,
  onClose,
  onSaved,
}: {
  open: boolean;
  group: GroupResponse | null;
  onClose: () => void;
  onSaved: (group: GroupResponse) => void;
}) {
  const {
    register,
    watch,
    reset,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<{ name: string }>();
  const [icon, setIcon] = useState('folder');
  const [color, setColor] = useState('blue');
  const lastGroup = useRef(group);
  useEffect(() => { if (open) lastGroup.current = group; }, [open, group]);
  const create = useCreateGroup(),
    update = useUpdateGroup();
  useEffect(() => {
    if (open) {
      reset({ name: group?.name || '' });
      setIcon(group?.icon || 'folder');
      setColor(group?.color || 'blue');
    }
  }, [open, group, reset]);
  const submit = handleSubmit(async ({ name }) => {
    const parsed = nameSchema.safeParse(name);
    if (!parsed.success) {
      setError('name', { message: parsed.error.issues[0].message });
      return;
    }
    try {
      const saved = group
        ? await update.mutateAsync({ id: group.id, name: parsed.data, icon, color })
        : await create.mutateAsync({ name: parsed.data, icon, color });
      onSaved(saved);
      onClose();
      toast.success(group ? 'Группа сохранена.' : 'Группа создана.');
    } catch {
      toast.error('Не удалось сохранить группу');
    }
  });
  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="munin-form-dialog group-editor-dialog">
        <DialogHeader>
          <DialogTitle>{(open ? group : lastGroup.current) ? 'Изменить группу' : 'Новая группа'}</DialogTitle>
          <DialogDescription>Название, значок и цвет помогут узнать её в списке.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit}>
          <div className="group-editor-grid">
            <div className="space-y-5">
              <label className="munin-field">
                Название
                <Input autoFocus {...register('name')} />
                {errors.name && <small className="text-destructive">{errors.name.message}</small>}
              </label>
              <div>
                <p className="editor-label">Значок</p>
                <div className="group-icon-options" role="group" aria-label="Значок группы">
                  {GROUP_ICON_KEYS.map((key) => {
                    const Icon = getGroupIcon(key);
                    return (
                      <button
                        type="button"
                        key={key}
                        aria-label={ICON_LABELS[key]}
                        aria-pressed={icon === key}
                        onClick={() => setIcon(key)}
                      >
                        <Icon />
                      </button>
                    );
                  })}
                </div>
              </div>
              <div>
                <p className="editor-label">Цвет метки</p>
                <div className="group-color-options" role="group" aria-label="Цвет группы">
                  {Object.entries(GROUP_COLORS).map(([key, value]) => (
                    <button
                      key={key}
                      type="button"
                      aria-label={COLORS_LABELS[key]}
                      aria-pressed={color === key}
                      onClick={() => setColor(key)}
                    >
                      <span style={{ background: value }} />
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <aside className="group-live-preview">
              <small>Предпросмотр</small>
              <div>
                <GroupEmblem group={{ icon, color }} />
                <strong>{watch('name')?.trim() || 'Название группы'}</strong>
                <small>Задачи этой группы</small>
              </div>
            </aside>
          </div>
          <div className="mt-6 flex gap-2">
            <Button disabled={create.isPending || update.isPending}>Сохранить</Button>
            <Button type="button" variant="outline" onClick={onClose}>
              Отмена
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
