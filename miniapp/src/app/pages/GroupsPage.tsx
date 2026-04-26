import { useState } from 'react';
import { IconPlus, IconCheck, IconFolderOpen } from '@tabler/icons-react';
import { motion } from 'motion/react';
import { useStore, Group } from '@/lib/store';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/app/components/ui/dialog';
import { GroupDetailModal } from '@/app/components/GroupDetailModal';
import { GROUP_ICON_KEYS, getGroupIcon } from '@/lib/group-icons';
const COLORS = ['blue', 'purple', 'green', 'orange', 'red', 'yellow', 'pink', 'cyan'];

const GROUP_CARD_STYLE: Record<string, string> = {
  blue: 'border-blue-200 dark:border-blue-800/50 bg-blue-50/50 dark:bg-blue-950/30',
  purple: 'border-purple-200 dark:border-purple-800/50 bg-purple-50/50 dark:bg-purple-950/30',
  green: 'border-green-200 dark:border-green-800/50 bg-green-50/50 dark:bg-green-950/30',
  orange: 'border-orange-200 dark:border-orange-800/50 bg-orange-50/50 dark:bg-orange-950/30',
  red: 'border-red-200 dark:border-red-800/50 bg-red-50/50 dark:bg-red-950/30',
  yellow: 'border-yellow-200 dark:border-yellow-800/50 bg-yellow-50/50 dark:bg-yellow-950/30',
  pink: 'border-pink-200 dark:border-pink-800/50 bg-pink-50/50 dark:bg-pink-950/30',
  cyan: 'border-cyan-200 dark:border-cyan-800/50 bg-cyan-50/50 dark:bg-cyan-950/30',
};

function CreateGroupModal({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const addGroup = useStore((s) => s.addGroup);
  const [name, setName] = useState('');
  const [icon, setIcon] = useState('briefcase');
  const [color, setColor] = useState('blue');

  const handleCreate = () => {
    if (!name.trim()) return;
    addGroup({ name: name.trim(), icon, color });
    setName('');
    setIcon('briefcase');
    setColor('blue');
    onClose();
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>Новая группа</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          {/* Name */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Название
            </label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              placeholder="Работа, Учёба, Хобби..."
              className="h-10"
              autoFocus
            />
          </div>

          {/* Icon picker */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Иконка
            </label>
            <div className="flex flex-wrap gap-2">
              {GROUP_ICON_KEYS.map((key) => {
                const Icon = getGroupIcon(key);
                const active = icon === key;
                return (
                  <button
                    key={key}
                    onClick={() => setIcon(key)}
                    className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors cursor-pointer ${
                      active
                        ? 'bg-primary/15 ring-2 ring-primary text-primary'
                        : 'hover:bg-muted text-muted-foreground'
                    }`}
                  >
                    <Icon className="h-4.5 w-4.5" />
                  </button>
                );
              })}
            </div>
          </div>

          {/* Color picker */}
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              Цвет
            </label>
            <div className="flex gap-2">
              {COLORS.map((c) => (
                <button
                  key={c}
                  onClick={() => setColor(c)}
                  className={`w-7 h-7 rounded-full flex items-center justify-center ring-offset-background transition-all cursor-pointer ${
                    color === c ? 'ring-2 ring-primary ring-offset-2' : ''
                  }`}
                  style={{ backgroundColor: getColorHex(c) }}
                >
                  {color === c && <IconCheck className="h-3.5 w-3.5 text-white" />}
                </button>
              ))}
            </div>
          </div>

          <Button onClick={handleCreate} disabled={!name.trim()} className="w-full h-10">
            Создать группу
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function getColorHex(color: string): string {
  const map: Record<string, string> = {
    blue: '#3b82f6',
    purple: '#a855f7',
    green: '#22c55e',
    orange: '#f97316',
    red: '#ef4444',
    yellow: '#eab308',
    pink: '#ec4899',
    cyan: '#06b6d4',
  };
  return map[color] ?? '#6b7280';
}

export function GroupsPage() {
  const groups = useStore((state) => state.groups);
  const tasks = useStore((state) => state.tasks);
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedGroup, setSelectedGroup] = useState<Group | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const getGroupStats = (groupName: string) => {
    const groupTasks = tasks.filter((t) => t.group === groupName);
    const done = groupTasks.filter((t) => t.status === 'DONE').length;
    const active = groupTasks.filter((t) => t.status !== 'DONE' && t.status !== 'CANCELLED').length;
    return { total: groupTasks.length, done, active };
  };

  const handleOpenGroup = (group: Group) => {
    setSelectedGroup(group);
    setDetailOpen(true);
  };

  return (
    <>
      <div className="max-w-4xl mx-auto space-y-5">
        <h1 className="text-2xl font-semibold">Группы</h1>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          <motion.button
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            onClick={() => setCreateOpen(true)}
            className="text-left w-full p-4 rounded-xl border border-dashed border-border hover:border-primary/50 hover:bg-accent/40 transition-all cursor-pointer space-y-3 group"
          >
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 bg-muted text-muted-foreground group-hover:bg-primary/10 group-hover:text-primary transition-colors">
                <IconPlus className="h-5 w-5" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="font-semibold text-sm">Новая группа</h3>
                <p className="text-xs text-muted-foreground">Создать вручную</p>
              </div>
            </div>
          </motion.button>

          {groups.map((group, i) => {
            const stats = getGroupStats(group.name);
            const cardStyle = GROUP_CARD_STYLE[group.color ?? 'blue'] ?? GROUP_CARD_STYLE.blue;
            const progress = stats.total > 0 ? (stats.done / stats.total) * 100 : 0;
            const Icon = getGroupIcon(group.icon);

            return (
              <motion.button
                key={group.id}
                initial={{ opacity: 0, y: 12 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.05 }}
                onClick={() => handleOpenGroup(group)}
                className={`text-left w-full p-4 rounded-xl border transition-all hover:shadow-md hover:-translate-y-0.5 hover:brightness-105 dark:hover:brightness-125 active:scale-98 space-y-3 cursor-pointer ${cardStyle}`}
              >
                {/* Header */}
                <div className="flex items-center gap-3">
                  <div
                    className="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0"
                    style={{ backgroundColor: `${getColorHex(group.color ?? 'blue')}22`, color: getColorHex(group.color ?? 'blue') }}
                  >
                    <Icon className="h-5 w-5" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <h3 className="font-semibold text-sm truncate">{group.name}</h3>
                    <p className="text-xs text-muted-foreground">
                      {stats.active > 0
                        ? `${stats.active} активных`
                        : stats.total > 0
                        ? 'Всё выполнено ✓'
                        : 'Пусто'}
                    </p>
                  </div>
                  {stats.total > 0 && (
                    <span className="text-xs font-medium text-muted-foreground flex-shrink-0">
                      {stats.done}/{stats.total}
                    </span>
                  )}
                </div>

                {/* Progress bar */}
                {stats.total > 0 && (
                  <div className="h-1 bg-black/10 dark:bg-white/10 rounded-full overflow-hidden">
                    <div
                      className="h-full bg-current opacity-40 rounded-full transition-all"
                      style={{ width: `${progress}%` }}
                    />
                  </div>
                )}
              </motion.button>
            );
          })}

        </div>

        {groups.length === 0 && (
          <div className="text-center py-16 space-y-3">
            <IconFolderOpen className="h-10 w-10 mx-auto text-muted-foreground/60" />
            <p className="text-muted-foreground">Создай первую группу для организации задач</p>
          </div>
        )}
      </div>

      <CreateGroupModal open={createOpen} onClose={() => setCreateOpen(false)} />
      <GroupDetailModal
        group={selectedGroup}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      />
    </>
  );
}
