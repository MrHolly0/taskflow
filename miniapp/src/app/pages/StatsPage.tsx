import { useStore } from '@/lib/store';
import { Card } from '@/app/components/ui/card';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

export function StatsPage() {
  const tasks = useStore((state) => state.tasks);

  const thisWeek = tasks.filter((task) => {
    const createdAt = new Date(task.createdAt);
    const weekAgo = new Date();
    weekAgo.setDate(weekAgo.getDate() - 7);
    return createdAt >= weekAgo;
  });

  const created = thisWeek.length;
  const done = thisWeek.filter((t) => t.status === 'DONE').length;
  const urgent = thisWeek.filter((t) => t.priority === 'URGENT').length;

  const activityData = Array.from({ length: 7 }, (_, i) => {
    const date = new Date();
    date.setDate(date.getDate() - (6 - i));
    const dayTasks = tasks.filter((task) => {
      const taskDate = new Date(task.createdAt);
      return taskDate.toDateString() === date.toDateString();
    });

    return {
      name: date.toLocaleDateString('ru-RU', { weekday: 'short' }),
      tasks: dayTasks.length,
    };
  });

  const streak = 5;

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      <h1 className="text-2xl font-semibold">Статистика</h1>

      <div>
        <h2 className="text-sm font-medium text-muted-foreground uppercase tracking-wide mb-3">Эта неделя</h2>
        <div className="grid grid-cols-3 gap-3">
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold">{created}</div>
            <div className="text-xs text-muted-foreground mt-1.5">создано</div>
          </Card>
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold">{done}</div>
            <div className="text-xs text-muted-foreground mt-1.5">сделано</div>
          </Card>
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold">{urgent}</div>
            <div className="text-xs text-muted-foreground mt-1.5">срочных</div>
          </Card>
        </div>
      </div>

      <Card className="p-5">
        <div className="flex items-center gap-4">
          <div className="text-4xl">🔥</div>
          <div>
            <div className="text-xl font-semibold">{streak} дней подряд</div>
            <p className="text-sm text-muted-foreground">Не пропускай завтра!</p>
          </div>
        </div>
      </Card>

      <Card className="p-5 space-y-4">
        <h2 className="text-sm font-medium">Активность по дням</h2>
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={activityData}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
            <XAxis
              dataKey="name"
              tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
              stroke="var(--border)"
            />
            <YAxis
              tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
              stroke="var(--border)"
              width={24}
              allowDecimals={false}
              domain={[0, (max: number) => Math.max(max, 4)]}
            />
            <Tooltip
              cursor={{ fill: 'var(--accent)', opacity: 0.5 }}
              contentStyle={{
                backgroundColor: 'var(--card)',
                color: 'var(--card-foreground)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                fontSize: '12px',
              }}
            />
            <Bar dataKey="tasks" fill="var(--primary)" radius={[6, 6, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Card>
    </div>
  );
}