import { useQuery } from '@tanstack/react-query';
import { useTaskUi } from '@/lib/task-ui';
import { taskDetailQuery } from '@/lib/hooks/useTasks';
import { TaskDetailModal } from '@/app/components/TaskDetailModal';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/app/components/ui/dialog';
import { Button } from '@/app/components/ui/button';

export function TaskInspector() {
  const id = useTaskUi(state => state.inspectTaskId);
  const inspect = useTaskUi(state => state.inspectTask);
  const task = useQuery(taskDetailQuery(id));
  if (!id) return null;
  if (task.data) return <TaskDetailModal task={task.data} open onClose={() => inspect(null)} />;
  return <Dialog open onOpenChange={open => { if (!open) inspect(null); }}>
    <DialogContent aria-describedby={undefined}>
      <DialogHeader><DialogTitle>Открываем задачу</DialogTitle></DialogHeader>
      {task.isError ? <><p role="alert">Не удалось загрузить задачу.</p><Button onClick={() => void task.refetch()}>Повторить</Button></> : <p>Загрузка…</p>}
    </DialogContent>
  </Dialog>;
}
