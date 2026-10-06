(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const statuses = ['TODO', 'IN_PROGRESS', 'DONE'];
  const labels = {TODO:'К выполнению', IN_PROGRESS:'В работе', DONE:'Готово', CANCELLED:'Отменено'};
  const icon = (name) => `<svg aria-hidden="true"><use href="#${name}"/></svg>`;
  const escape = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const initial = [
    {id:1,title:'Подготовить структуру диплома',group:'Учёба',status:'TODO',priority:'HIGH',estimate:30,deadline:'2026-10-06',description:'Разобрать главы и вопросы для встречи с руководителем.',reminder:true},
    {id:2,title:'Отправить документы в деканат',group:'Учёба',status:'TODO',priority:'MEDIUM',estimate:15,deadline:'2026-10-07'},
    {id:3,title:'Проверить правки к макету и собрать комментарии перед следующей встречей',group:'Работа',status:'TODO',priority:'HIGH',estimate:20},
    {id:4,title:'Записаться к стоматологу',group:'Личное',status:'TODO',priority:'LOW',estimate:10},
    {id:5,title:'Купить продукты на неделю',group:'Личное',status:'TODO',priority:'MEDIUM',estimate:30},
    {id:6,title:'Разобрать заметки за неделю',group:'Личное',status:'IN_PROGRESS',priority:'MEDIUM',estimate:15},
    {id:7,title:'Обновить резюме',group:'Работа',status:'IN_PROGRESS',priority:'MEDIUM',estimate:30},
    {id:8,title:'Набросать идею для портфолио',group:'Идеи',status:'IN_PROGRESS',priority:'LOW'},
    {id:9,title:'Отправить макет',group:'Работа',status:'DONE',priority:'MEDIUM',estimate:20},
    {id:10,title:'Позвонить Саше',group:'Личное',status:'DONE',priority:'LOW',estimate:10},
    {id:11,title:'Подготовиться к встрече с руководителем',group:'Учёба',status:'DONE',priority:'HIGH',estimate:45}
  ];
  let tasks = initial.map(t => ({...t})), nextId=100, query='', stress=false, edited=null, createStatus=null;
  const narrowScreen=matchMedia('(max-width:767px)');
  const batch=()=>narrowScreen.matches?4:8;
  const freshLimits=()=>({TODO:batch(),IN_PROGRESS:batch(),DONE:batch()});
  let limits=freshLimits();
  let view; try {view=localStorage.getItem('munin.board-concept.view') === 'horizontal'?'horizontal':'vertical';} catch {view='vertical';}
  let toastTimer, menuTask, gesture=null, drag=null, dragFrame=null, pointer={x:0,y:0}, carouselIndex=0, blockClickUntil=0;
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const filtered = (status) => tasks.filter(t => t.status === status && (!query || `${t.title} ${t.group ?? ''}`.toLocaleLowerCase('ru').includes(query)));
  function notify(text) {
    clearTimeout(toastTimer); $('toast').textContent=text; $('toast').hidden=false; $('toast').classList.remove('closing');
    toastTimer=setTimeout(() => {$('toast').classList.add('closing');toastTimer=setTimeout(() => {$('toast').hidden=true;},230);},3400);
  }
  function taskCard(task) {
    const date=task.deadline?new Intl.DateTimeFormat('ru',{day:'numeric',month:'short'}).format(new Date(task.deadline+'T12:00:00')):'';
    return `<article class="task" data-id="${task.id}" data-detail="${task.id}" data-drag="${task.id}" tabindex="0" aria-label="${escape(task.title)}" aria-describedby="board-drag-hint"><div class="task-main"><span class="task-grip" aria-hidden="true">${icon('grip')}</span><div class="task-content"><div class="task-title">${escape(task.title)}</div><div class="task-meta">${task.priority==='HIGH'||task.priority==='URGENT'?`<span>${task.priority==='URGENT'?'Срочно':'Важно'}</span>`:''}${date?`<span>${icon('clock')}${date}</span>`:''}${task.group?`<span>${escape(task.group)}</span>`:''}${task.reminder?`<span>${icon('bell')}09:00</span>`:''}</div></div><button class="icon-button" data-menu="${task.id}" aria-label="Быстрые действия: ${escape(task.title)}">${icon('more')}</button></div></article>`;
  }
  function render(revealId) {
    const scrolled = Object.fromEntries([...document.querySelectorAll('.column')].map(col => [col.dataset.status,col.querySelector('.column-body').scrollTop]));
    const horizontalScroll=$('board').scrollLeft;
    $('summary').textContent=`${tasks.filter(t=>t.status==='TODO'||t.status==='IN_PROGRESS').length} активных · ${tasks.filter(t=>t.status==='DONE').length} готово`;
    $('board').innerHTML=statuses.map(status => {
      const all=filtered(status), shown=all.slice(0,limits[status]);
      return `<section class="column" data-status="${status}" aria-label="${labels[status]}"><header class="column-head"><h2><svg class="status-icon" aria-hidden="true"><use href="#${{TODO:'status-todo',IN_PROGRESS:'status-progress',DONE:'status-done'}[status]}"/></svg>${labels[status]}<small>${all.length}</small></h2><button class="icon-button" data-add="${status}" aria-label="Добавить задачу: ${labels[status]}">${icon('plus')}</button></header>${createStatus===status?`<form class="inline-create" data-create="${status}"><textarea maxlength="512" rows="2" required aria-label="Название новой задачи" placeholder="Что нужно сделать?"></textarea><div class="inline-actions"><button class="button primary" type="submit">Добавить</button><button class="button" type="button" data-create-close>Отмена</button></div><small class="local-note">Ручное создание в «${labels[status]}»</small></form>`:''}<div class="column-body">${shown.map(taskCard).join('')}${!all.length?`<div class="empty-column"><span>${query?'Задач по запросу нет':status==='DONE'?'Здесь появятся завершённые дела':'В этой колонке пока пусто'}</span>${!query?`<button class="button" data-add="${status}">${icon('plus')}Задача</button>`:''}</div>`:''}${all.length>shown.length?`<button class="load-more" data-more="${status}">Ещё ${Math.min(batch(),all.length-shown.length)} из ${all.length-shown.length}</button>`:''}</div></section>`;
    }).join('');
    document.querySelectorAll('.column').forEach(col=>{col.querySelector('.column-body').scrollTop=scrolled[col.dataset.status]??0;});
    $('board').scrollLeft=horizontalScroll;
    if(createStatus) requestAnimationFrame(() => document.querySelector('.inline-create textarea')?.focus());
    if(revealId) requestAnimationFrame(() => {const card=document.querySelector(`.task[data-id="${revealId}"]`);if(card){card.classList.add('reveal');card.scrollIntoView({block:'nearest',inline:'nearest',behavior:reduced.matches?'instant':'smooth'});}});
    updateStatusNav();
  }
  function changeStatus(id,status) {
    const task=tasks.find(t=>t.id===Number(id));if(!task || task.status===status)return;
    task.status=status;tasks=[task,...tasks.filter(t=>t.id!==task.id)];query='';$('search').value='';$('clear-search').hidden=true;limits[status]=Math.max(limits[status]??batch(),batch());closeMenu();render(task.id);notify(`«${task.title}»: ${labels[status].toLocaleLowerCase('ru')}`);
  }
  function setView(next) {
    view=next;$('board').dataset.view=view;document.querySelectorAll('[data-view]').forEach(button=>{if(button.tagName==='BUTTON')button.setAttribute('aria-pressed',String(button.dataset.view===view));});
    $('carousel-nav').classList.toggle('visible',view==='horizontal');try{localStorage.setItem('munin.board-concept.view',view);}catch{}
    if(view==='horizontal')requestAnimationFrame(()=>goToStatus(carouselIndex));
  }
  function updateStatusNav() {
    $('status-nav').innerHTML=statuses.map((status,index)=>`<button data-column-index="${index}" aria-pressed="${index===carouselIndex}">${labels[status]}</button>`).join('');
    $('prev').disabled=carouselIndex===0;$('next').disabled=carouselIndex===2;
  }
  function goToStatus(index) {
    carouselIndex=Math.max(0,Math.min(2,index));const col=$('board').children[carouselIndex];if(col)$('board').scrollTo({left:col.offsetLeft-$('board').children[0].offsetLeft,behavior:reduced.matches?'instant':'smooth'});updateStatusNav();
  }
  function closeMenu() {$('quick-menu').hidden=true;document.querySelector('.task-menu-lift')?.classList.remove('task-menu-lift');}
  function openMenu(id,x,y) {
    const task=tasks.find(t=>t.id===Number(id));if(!task)return;menuTask=task;closeMenu();
    const actions=[{label:'Открыть детали',action:'detail'},...statuses.filter(s=>s!==task.status).map(s=>({label:s==='TODO'?'Вернуть в список':s==='IN_PROGRESS'?'Взять в работу':'Завершить',action:s})),{label:'Отменить задачу',action:'CANCELLED'}];
    $('quick-menu').innerHTML=`<h3>${escape(task.title)}</h3>${actions.map(a=>`<button role="menuitem" data-menu-action="${a.action}" class="${a.action==='CANCELLED'?'danger':''}">${escape(a.label)}</button>`).join('')}`;
    $('quick-menu').hidden=false;const rect=$('quick-menu').getBoundingClientRect();$('quick-menu').style.left=Math.max(12,Math.min(x,innerWidth-rect.width-12))+'px';$('quick-menu').style.top=Math.max(12,Math.min(y,innerHeight-rect.height-90))+'px';
    if(matchMedia('(pointer:coarse)').matches)document.querySelector(`.task[data-id="${id}"]`)?.classList.add('task-menu-lift');
    $('quick-menu').querySelector('button')?.focus();
  }
  function picker(id,options,value) {
    $(id).dataset.value=value;$(id).innerHTML=`<details><summary>${escape(options.find(o=>o[0]===value)?.[1]??value)}</summary><div class="picker-options" role="listbox">${options.map(([v,label])=>`<button type="button" role="option" aria-selected="${v===value}" data-value="${escape(v)}">${escape(label)}</button>`).join('')}</div></details>`;
    $(id).querySelectorAll('button').forEach(button=>button.addEventListener('click',()=>{$(id).dataset.value=button.dataset.value;$(id).querySelector('summary').textContent=button.textContent;$(id).querySelectorAll('button').forEach(b=>b.setAttribute('aria-selected',String(b===button)));$(id).querySelector('details').open=false;}));
  }
  function openDetails(id) {
    closeMenu();edited=tasks.find(t=>t.id===Number(id));if(!edited)return;
    $('edit-title').value=edited.title;$('edit-description').value=edited.description??'';$('edit-estimate').value=edited.estimate??'';$('edit-deadline').value=edited.deadline??'';$('edit-reminder').checked=Boolean(edited.reminder);
    picker('edit-status',Object.entries(labels),edited.status);picker('edit-group',['','Учёба','Личное','Работа','Идеи'].map(g=>[g,g||'Без группы']),edited.group??'');picker('edit-priority',[['LOW','Когда будет время'],['MEDIUM','Средний'],['HIGH','Важно'],['URGENT','Срочно']],edited.priority);
    $('task-dialog').showModal();$('edit-title').focus();
  }
  document.addEventListener('click',event=>{if(Date.now()<blockClickUntil && !event.target.closest('#quick-menu')){event.preventDefault();event.stopImmediatePropagation();}},true);
  document.addEventListener('click',event=>{
    const detail=event.target.closest('[data-detail]'), complete=event.target.closest('[data-complete]'), status=event.target.closest('[data-status][data-id]'), menu=event.target.closest('[data-menu]'), add=event.target.closest('[data-add]'), more=event.target.closest('[data-more]'), mode=event.target.closest('button[data-view]'), index=event.target.closest('[data-column-index]');
    if(menu){const r=menu.getBoundingClientRect();openMenu(menu.dataset.menu,r.right-258,r.bottom+5);}
    else if(detail)openDetails(detail.dataset.detail);
    else if(complete){const task=tasks.find(t=>t.id===Number(complete.dataset.complete));changeStatus(task.id,task.status==='DONE'?'TODO':'DONE');}
    else if(status)changeStatus(status.dataset.id,status.dataset.status);
    else if(add){createStatus=add.dataset.add;render();document.querySelector('.inline-create')?.scrollIntoView({block:'nearest',behavior:reduced.matches?'instant':'smooth'});}
    else if(more){limits[more.dataset.more]+=batch();render();}
    else if(mode)setView(mode.dataset.view);
    else if(index)goToStatus(Number(index.dataset.columnIndex));
    else if(event.target.closest('[data-create-close]')){createStatus=null;render();}
    else if(event.target.closest('[data-close]'))event.target.closest('dialog').close();
    else if(event.target.closest('[data-capture]')){$('capture-dialog').showModal();$('capture-text').focus();}
    else if(event.target.closest('[data-menu-action]')){const action=event.target.closest('[data-menu-action]').dataset.menuAction;if(action==='detail')openDetails(menuTask.id);else changeStatus(menuTask.id,action);}
    else if(!event.target.closest('#quick-menu'))closeMenu();
  });
  $('board').addEventListener('submit',event=>{const form=event.target.closest('[data-create]');if(!form)return;event.preventDefault();const title=form.querySelector('textarea').value.trim();if(!title)return;const task={id:nextId++,title,status:form.dataset.create,priority:'MEDIUM'};tasks.unshift(task);createStatus=null;query='';$('search').value='';$('clear-search').hidden=true;render(task.id);notify('Задача добавлена в выбранную колонку.');});
  $('task-form').addEventListener('submit',event=>{event.preventDefault();if(!edited)return;const title=$('edit-title').value.trim();if(!title)return;Object.assign(edited,{title,description:$('edit-description').value,estimate:Number($('edit-estimate').value)||undefined,deadline:$('edit-deadline').value||undefined,reminder:$('edit-reminder').checked,status:$('edit-status').dataset.value,group:$('edit-group').dataset.value,priority:$('edit-priority').dataset.value});$('task-dialog').close();tasks=[edited,...tasks.filter(t=>t.id!==edited.id)];query='';$('search').value='';$('clear-search').hidden=true;render(edited.id);notify('Задача сохранена.');});
  $('capture-form').addEventListener('submit',event=>{event.preventDefault();const title=$('capture-text').value.trim();if(!title)return;const task={id:nextId++,title,status:'TODO',priority:'MEDIUM'};tasks.unshift(task);$('capture-dialog').close();$('capture-text').value='';query='';$('search').value='';$('clear-search').hidden=true;render(task.id);notify('Добавлено в локальный пример.');});
  document.querySelectorAll('dialog').forEach(dialog=>{let outside=false;const isOutside=e=>{const r=dialog.getBoundingClientRect();return e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom;};dialog.addEventListener('pointerdown',e=>{outside=e.target===dialog&&isOutside(e);});dialog.addEventListener('click',e=>{if(outside&&e.target===dialog&&isOutside(e))dialog.close();outside=false;});});
  $('search').addEventListener('input',event=>{query=event.target.value.trim().toLocaleLowerCase('ru');$('clear-search').hidden=!query;limits=freshLimits();render();});$('clear-search').addEventListener('click',()=>{$('search').value='';query='';$('clear-search').hidden=true;render();$('search').focus();});
  $('stress').addEventListener('click',()=>{if(stress){tasks=tasks.filter(t=>!t.stress);stress=false;}else{for(let i=0;i<60;i++)tasks.push({id:nextId++,title:`${['Разобрать входящие записи','Сверить правки по проекту','Подготовить материалы к встрече'][i%3]} · ${i+1}`,group:['Учёба','Работа','Личное'][i%3],status:i<50?'TODO':i<56?'IN_PROGRESS':'DONE',priority:'MEDIUM',estimate:15,stress:true});stress=true;}$('stress').textContent=stress?'Обычный пример':'50+ задач';render();});
  $('empty').addEventListener('click',()=>{tasks.filter(t=>t.status==='DONE').forEach(t=>{t.status='CANCELLED';});render();notify('Готовые задачи скрыты только в этом примере. Сброс возвращает их.');});
  $('theme').addEventListener('click',()=>{const dark=document.documentElement.dataset.theme!=='dark';document.documentElement.dataset.theme=dark?'dark':'light';$('theme').textContent=dark?'Светлая тема':'Тёмная тема';});
  $('reset').addEventListener('click',()=>{cancelDrag();tasks=initial.map(t=>({...t}));stress=false;query='';createStatus=null;limits=freshLimits();$('stress').textContent='50+ задач';$('search').value='';$('clear-search').hidden=true;closeMenu();render();notify('Пример сброшен.');});
  $('account').addEventListener('click',()=>notify('Тема примера меняется в верхней полосе. В приложении она находится в настройках.'));
  $('mobile-more').addEventListener('click',()=>{location.href='concept.html#settings';});
  $('prev').addEventListener('click',()=>goToStatus(carouselIndex-1));$('next').addEventListener('click',()=>goToStatus(carouselIndex+1));
  $('board').addEventListener('scroll',()=>{if(view!=='horizontal'||!matchMedia('(max-width:767px)').matches)return;const first=$('board').children[0];const step=first.getBoundingClientRect().width+12;const index=Math.max(0,Math.min(2,Math.round($('board').scrollLeft/step)));if(index!==carouselIndex){carouselIndex=index;updateStatusNav();}},{passive:true});
  $('board').addEventListener('contextmenu',event=>{const card=event.target.closest('.task');if(!card)return;event.preventDefault();openMenu(card.dataset.id,event.clientX,event.clientY);});
  function hitTest(x,y) {
    const cancel=$('cancel-zone').getBoundingClientRect();const overCancel=x>=cancel.left&&x<=cancel.right&&y>=cancel.top&&y<=cancel.bottom;$('cancel-zone').classList.toggle('is-over',overCancel);if(overCancel)return null;
    const main=$('main').getBoundingClientRect();if(x<main.left||x>main.right||y<main.top||y>main.bottom)return null;
    return [...document.querySelectorAll('.column')].find(col=>{const r=col.getBoundingClientRect();return x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom;})?.dataset.status??null;
  }
  function selectTarget(status) {
    drag.target=status;document.querySelectorAll('.column').forEach(col=>col.classList.toggle('is-target',col.dataset.status===status&&status!==drag.task.status));
  }
  function moveGhost(x,y) {
    drag.ghost.style.transform=`translate3d(${x-drag.offsetX}px,${y-drag.offsetY}px,0) rotate(1deg)`;
  }
  function startDrag(id,x,y,keyboard=false) {
    const task=tasks.find(t=>t.id===Number(id));if(!task)return;closeMenu();
    const source=document.querySelector(`.task[data-id="${id}"]`),r=source.getBoundingClientRect();
    const ghost=source.cloneNode(true);ghost.classList.add('drag-ghost');ghost.classList.remove('reveal');ghost.removeAttribute('data-id');ghost.removeAttribute('data-detail');ghost.removeAttribute('data-drag');ghost.removeAttribute('tabindex');ghost.setAttribute('aria-hidden','true');ghost.querySelectorAll('button').forEach(b=>b.tabIndex=-1);ghost.style.width=r.width+'px';document.body.append(ghost);
    source.classList.add('drag-source');
    drag={task,source,ghost,target:null,keyboard,offsetX:x-r.left,offsetY:y-r.top};pointer={x,y};document.body.classList.add('dragging');$('board').classList.add('drag-active');$('cancel-zone').hidden=false;
    moveGhost(x,y);$('announcer').textContent=`Взята задача ${task.title}. Для отмены нажми Escape.`;
    if(keyboard)selectTarget(task.status);else autoscroll();
  }
  function autoscroll() {
    if(!drag||drag.keyboard)return;const narrow=matchMedia('(max-width:767px)').matches;
    const col=document.querySelector(`.column[data-status="${drag.target}"]`), body=col?.querySelector('.column-body');
    if(body&&getComputedStyle(body).overflowY!=='visible') {const r=body.getBoundingClientRect();if(pointer.x>=r.left&&pointer.x<=r.right){if(pointer.y>r.bottom-55)body.scrollTop+=8;else if(pointer.y<r.top+45)body.scrollTop-=8;}}
    const main=$('main').getBoundingClientRect();if(pointer.y>main.bottom-65)$('main').scrollTop+=8;else if(pointer.y<main.top+35)$('main').scrollTop-=8;
    if(narrow&&view==='horizontal'){const r=$('board').getBoundingClientRect();if(pointer.y>r.top&&pointer.y<r.bottom){if(pointer.x>innerWidth-42)$('board').scrollLeft+=7;else if(pointer.x<42)$('board').scrollLeft-=7;}}
    selectTarget(hitTest(pointer.x,pointer.y));dragFrame=requestAnimationFrame(autoscroll);
  }
  function cancelDrag(keepGhost=false) {
    if(gesture?.timer)clearTimeout(gesture.timer);gesture=null;cancelAnimationFrame(dragFrame);dragFrame=null;
    if(drag){drag.source.classList.remove('drag-source');if(!keepGhost)drag.ghost.remove();}drag=null;document.body.classList.remove('dragging');$('board').classList.remove('drag-active');$('cancel-zone').hidden=true;document.querySelectorAll('.is-target').forEach(c=>c.classList.remove('is-target'));
  }
  function finishDrag(target) {
    if(!drag)return;const task=drag.task,sourceStatus=task.status,ghost=drag.ghost;cancelDrag(true);
    if(target&&target!==sourceStatus)changeStatus(task.id,target);else notify('Перенос отменён.');
    requestAnimationFrame(()=>{const destination=document.querySelector(`.task[data-id="${task.id}"]`);if(!destination){ghost.remove();return;}const r=destination.getBoundingClientRect();ghost.style.transition=reduced.matches?'none':'transform 180ms cubic-bezier(.2,.7,.3,1), opacity 180ms';ghost.style.transform=`translate3d(${r.left}px,${r.top}px,0) rotate(0deg)`;ghost.style.opacity='0';setTimeout(()=>ghost.remove(),reduced.matches?0:180);});
  }
  $('board').addEventListener('pointerdown',event=>{
    if(event.button!==0||event.target.closest('button,input,textarea'))return;
    const card=event.target.closest('.task');if(!card)return;
    gesture={id:Number(card.dataset.id),x:event.clientX,y:event.clientY,pointerId:event.pointerId,type:event.pointerType,timer:null};
    if(event.pointerType!=='mouse')gesture.timer=setTimeout(()=>{if(gesture)startDrag(gesture.id,gesture.x,gesture.y);},200);
  });
  document.addEventListener('pointermove',event=>{
    if(gesture&&event.pointerId===gesture.pointerId){const distance=Math.hypot(event.clientX-gesture.x,event.clientY-gesture.y);if(!drag&&gesture.type==='mouse'&&distance>=8)startDrag(gesture.id,gesture.x,gesture.y);else if(!drag&&gesture.type!=='mouse'&&distance>5){clearTimeout(gesture.timer);gesture=null;}}
    if(drag&&!drag.keyboard){event.preventDefault();pointer={x:event.clientX,y:event.clientY};moveGhost(pointer.x,pointer.y);selectTarget(hitTest(pointer.x,pointer.y));}
  },{passive:false});
  document.addEventListener('pointerup',event=>{if(drag&&!drag.keyboard){blockClickUntil=Date.now()+350;finishDrag(hitTest(event.clientX,event.clientY));}else if(gesture){clearTimeout(gesture.timer);gesture=null;}});
  document.addEventListener('pointercancel',()=>{if(drag)cancelDrag();else if(gesture){clearTimeout(gesture.timer);gesture=null;}});
  document.addEventListener('keydown',event=>{
    if(event.key==='Escape'){if(drag){event.preventDefault();finishDrag(null);}else closeMenu();}
    if(drag?.keyboard){if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();const index=statuses.indexOf(drag.target??drag.task.status),next=Math.max(0,Math.min(2,index+(['ArrowRight','ArrowDown'].includes(event.key)?1:-1)));selectTarget(statuses[next]);const r=document.querySelector(`.column[data-status="${statuses[next]}"]`).getBoundingClientRect();drag.ghost.style.transform=`translate(${Math.min(r.left+20,innerWidth-270)}px,${r.top+65}px)`;$('announcer').textContent=labels[statuses[next]];}else if(event.key==='Enter'||event.key===' '){event.preventDefault();finishDrag(drag.target);}return;}
    const card=event.target.closest('[data-drag]');if(card&&event.target===card){if(event.key===' '){event.preventDefault();const r=card.getBoundingClientRect();startDrag(card.dataset.drag,r.left,r.top,true);}else if(event.key==='Enter'){event.preventDefault();openDetails(card.dataset.id);}}
    if(!$('quick-menu').hidden&&['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();const buttons=[...$('quick-menu').querySelectorAll('button')],index=buttons.indexOf(document.activeElement);buttons[event.key==='Home'?0:event.key==='End'?buttons.length-1:(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length].focus();}
  });
  window.addEventListener('resize',()=>{if(drag)cancelDrag();});
  narrowScreen.addEventListener('change',()=>{limits=freshLimits();render();});
  render();setView(view);
})();
