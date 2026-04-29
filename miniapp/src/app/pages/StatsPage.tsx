import { Card } from '@/app/components/ui/card';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from 'recharts';
import { useTasksList } from '@/lib/hooks/useTasks';

export function StatsPage() {
  const { data: allTasks = [] } = useTasksList();

  const now = new Date();
  const weekAgo = new Date(now);
  weekAgo.setDate(weekAgo.getDate() - 7);

  const created = allTasks.filter((t: any) => new Date(t.createdAt) >= weekAgo).length;
  const done = allTasks.filter((t: any) => t.completedAt && new Date(t.completedAt) >= weekAgo).length;
  const overdue = allTasks.filter((t: any) =>
    t.deadline && new Date(t.deadline) < now && t.status !== 'DONE' && t.status !== 'CANCELLED'
  ).length;

  const activityData = Array.from({ length: 7 }, (_, i) => {
    const date = new Date();
    date.setDate(date.getDate() - (6 - i));
    const dayStr = date.toDateString();

    const totalDay = allTasks.filter((task: any) =>
      new Date(task.createdAt).toDateString() === dayStr
    ).length;

    const completedDay = allTasks.filter((task: any) =>
      task.completedAt && new Date(task.completedAt).toDateString() === dayStr
    ).length;

    return {
      name: date.toLocaleDateString('ru-RU', { weekday: 'short' }),
      создано: totalDay,
      сделано: completedDay,
    };
  });

  const activeDays = new Set(
    allTasks.map((t: any) => new Date(t.createdAt).toDateString())
  ).size;

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
            <div className="text-3xl font-bold text-green-600 dark:text-green-400">{done}</div>
            <div className="text-xs text-muted-foreground mt-1.5">сделано</div>
          </Card>
          <Card className="p-5 text-center">
            <div className="text-3xl font-bold text-red-500">{overdue}</div>
            <div className="text-xs text-muted-foreground mt-1.5">просрочено</div>
          </Card>
        </div>
      </div>

      <Card className="p-5">
        <div className="flex items-center gap-4">
          <div className="text-4xl">🔥</div>
          <div>
            <div className="text-xl font-semibold">{activeDays} активных дней</div>
            <p className="text-sm text-muted-foreground">Не пропускай завтра!</p>
          </div>
        </div>
      </Card>

      <Card className="p-5 space-y-4">
        <h2 className="text-sm font-medium">Активность по дням</h2>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={activityData} barCategoryGap="30%">
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
            <Legend
              wrapperStyle={{ fontSize: '12px', color: 'var(--muted-foreground)' }}
            />
            <Bar dataKey="создано" fill="var(--muted-foreground)" fillOpacity={0.3} radius={[4, 4, 0, 0]} />
            <Bar dataKey="сделано" fill="var(--primary)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </Card>
    </div>
  );
}
