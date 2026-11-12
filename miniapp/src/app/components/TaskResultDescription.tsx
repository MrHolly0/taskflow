import { useId, useState } from 'react';
import '@/styles/task-result-description.css';

export function TaskResultDescription({description,title,onExpandedChange}:{description?:string|null;title?:string;onExpandedChange?:(expanded:boolean)=>void}) {
  const [expanded,setExpanded]=useState(false);
  const id=useId();
  const text=description?.trim();
  const normalized=(value:string)=>value.replace(/\s+/gu,' ').replace(/[.!?…]+$/gu,'').toLocaleLowerCase();
  if(!text || (title && normalized(text)===normalized(title.trim()))) return null;
  const lengthy=text.length>240;
  return <div className="task-result-description">
    <p className="task-result-description-label">Описание</p>
    <p id={id} className={lengthy&&!expanded?'task-result-description-clamped':undefined}>{text}</p>
    {lengthy && <button type="button" aria-expanded={expanded} aria-controls={id} onClick={()=>{
      const next=!expanded;setExpanded(next);onExpandedChange?.(next);
    }}>{expanded?'Свернуть описание':'Развернуть описание'}</button>}
  </div>;
}
