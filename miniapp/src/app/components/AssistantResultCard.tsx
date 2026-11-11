import '@/styles/assistant-result.css';
import { completionFeedback } from '@/lib/completion-feedback';
import { useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { IconCheck, IconPencil, IconArrowBackUp, IconX } from '@tabler/icons-react';
import { type ApplyResult, type Proposal, useUndoAssistantAction } from '@/lib/hooks/useAssistant';
import { taskDetailQuery } from '@/lib/hooks/useTasks';
import { useUserTimezone } from '@/lib/hooks/useUserTimezone';
import { useTaskUi } from '@/lib/task-ui';
import { cn, formatReminderTime } from '@/lib/utils';
import { Card } from '@/app/components/ui/card';
import { Button } from '@/app/components/ui/button';

export function AssistantResultCard({result,proposal,className,onDismiss}:{result:ApplyResult;proposal:Proposal;className?:string;onDismiss?:()=>void}) {
  const successful=result.outcomes.filter(item=>item.success);
  const [undone,setUndone]=useState<string[]>([]);
  const details=useQueries({queries:successful.map(item=>{
    const created=proposal.actions.find(action=>action.ordinal===item.ordinal)?.type==='CREATE';
    const removed=created&&(item.undone||Boolean(item.undoId&&undone.includes(item.undoId)));
    return {...taskDetailQuery(item.taskId??null),enabled:Boolean(item.taskId)&&!removed};
  })});
  const [failure,setFailure]=useState<string|null>(null);
  const undo=useUndoAssistantAction();
  const inspect=useTaskUi(state=>state.inspectTask);
  const {timezone,isReady}=useUserTimezone();
  const completion=successful.length===1 && proposal.actions.find(action=>action.ordinal===successful[0].ordinal)?.type==='COMPLETE';
  const creation=successful.length>0 && successful.every(item=>proposal.actions.find(action=>action.ordinal===item.ordinal)?.type==='CREATE');
  const allUndone=successful.length>0&&successful.every(item=>item.undone||Boolean(item.undoId&&undone.includes(item.undoId)));
  const heading=allUndone?'Действие отменено':result.appliedCount!==result.totalCount?'Сохранено частично':completion?completionFeedback(successful[0].undoId):creation?(successful.length===1?'Задача создана':'Задачи созданы'):'Изменения сохранены';
  return <Card className={cn('assistant-result-card',className)}>
    <header className="assistant-result-heading">
      <h2><IconCheck aria-hidden="true" />{successful.length?heading:'Не получилось выполнить действие'}</h2>
      {onDismiss && <button type="button" className="assistant-result-close" aria-label="Закрыть результат" onClick={onDismiss}><IconX aria-hidden="true" /></button>}
    </header>
    {successful.map((outcome,index)=> {
      const action=proposal.actions.find(item=>item.ordinal===outcome.ordinal);
      const reverted=outcome.undone || Boolean(outcome.undoId && undone.includes(outcome.undoId));
      const task=details[index]?.data;
      const title=task?.title ?? (typeof action?.payload.title==='string'?action.payload.title:outcome.summary);
      const reminders=task?.reminders?.filter(item=>item.status==='PENDING' && Date.parse(item.fireAt)>Date.now())
        .sort((a,b)=>Date.parse(a.fireAt)-Date.parse(b.fireAt))??[];
      const canUndo=outcome.undoId && !reverted && (!outcome.undoExpiresAt || Date.parse(outcome.undoExpiresAt)>Date.now());
      return <section key={outcome.ordinal}>
        <h3>{title}</h3>
        {reverted?<p role="status">{action?.type==='CREATE'?'Создание отменено':'Предыдущий статус возвращён'}</p>
          :action?.type==='CREATE' && task && isReady?<dl>
            <div><dt>Срок</dt><dd>{task.deadline?formatReminderTime(task.deadline,timezone):'Без срока'}</dd></div>
            <div><dt>{reminders.length>1?'Напоминания':'Напомнить'}</dt><dd>{reminders.length?reminders.map(reminder=><span className="block" key={reminder.id}>{formatReminderTime(reminder.fireAt,timezone)}</span>):'Не назначено'}</dd></div>
          </dl>:null}
        <div className="assistant-result-actions">
          {outcome.taskId && !(reverted&&action?.type==='CREATE') && <Button variant="outline" disabled={undo.isPending} onClick={()=>inspect(outcome.taskId!)}><IconPencil aria-hidden="true" />Изменить</Button>}
          {canUndo && <Button variant="outline" disabled={undo.isPending} onClick={()=> {
            setFailure(null);
            undo.mutate(outcome.undoId!,{onSuccess:()=>setUndone(previous=>[...previous,outcome.undoId!]),onError:error=> {
              const message=isAxiosError(error)&&typeof error.response?.data?.detail==='string'?error.response.data.detail:'Не получилось отменить, попробуй ещё раз';
              setFailure(message);
            }});
          }}><IconArrowBackUp aria-hidden="true" />{undo.isPending&&undo.variables===outcome.undoId?'Отменяем…':'Отменить'}</Button>}
        </div>
      </section>;
    })}
    {result.outcomes.filter(item=>!item.success).map(item=><p className="text-destructive" key={item.ordinal}>{item.summary}: {item.error??'Не удалось сохранить'}</p>)}
    {failure && <p className="text-destructive" role="alert">{failure}</p>}
  </Card>;
}
