const frame=document.getElementById('frame');
const bubble=document.getElementById('bubble');
const stage=document.getElementById('stage');
let html='',examples={},kind='reminder',theme='light',width=393,fail=false,generation=0;
let current;
const palettes={light:{bg_color:'#ffffff',text_color:'#121419',hint_color:'#707784',secondary_bg_color:'#f0f2f6',link_color:'#496bef',section_separator_color:'#e3e6ec',destructive_text_color:'#b73544'},dark:{bg_color:'#19222e',text_color:'#edf1f6',hint_color:'#a4afbd',secondary_bg_color:'#232e3d',link_color:'#96afff',section_separator_color:'#374354',destructive_text_color:'#ff96a2'}};
function paint(reset=true){
 generation++;if(reset||!current)current=structuredClone(examples[kind]);
 stage.classList.toggle('dark',theme==='dark');bubble.style.maxWidth=width+'px';
 document.getElementById('source').classList.toggle('show',kind==='proposal');
 document.querySelectorAll('[data-kind]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.kind===kind)));
 document.querySelectorAll('[data-theme]').forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.theme===theme)));
 document.querySelectorAll('[data-width]').forEach(b=>b.setAttribute('aria-pressed',String(Number(b.dataset.width)===width)));
 const css=Object.entries(palettes[theme]).map(([key,value])=>'--tg-theme-'+key.replaceAll('_','-')+':'+value).join(';');
 const sdk=`<script>var listeners={},pending={},seq=0;window.TelegramWidget={chatData:${JSON.stringify(JSON.stringify(current))},colorScheme:${JSON.stringify(theme)},onEvent:function(type,fn){listeners[type]=fn},ready:function(){size()},request:function(code){return new Promise(function(resolve,reject){var id=++seq;pending[id]={resolve:resolve,reject:reject};parent.postMessage({type:'widget-request',id:id,code:code},'*')})}};function size(){parent.postMessage({type:'widget-size',height:Math.ceil(document.body.getBoundingClientRect().height)},'*')}addEventListener('message',function(e){if(e.source!==parent||!e.data||e.data.type!=='widget-answer')return;var p=pending[e.data.id];if(!p)return;delete pending[e.data.id];if(e.data.error){p.reject(new Error(e.data.error));return}p.resolve({result:JSON.stringify(e.data.result)});if(e.data.result.state){TelegramWidget.chatData=JSON.stringify(e.data.result.state);if(listeners.chatDataChanged)listeners.chatDataChanged()}setTimeout(size,20)});addEventListener('load',function(){new ResizeObserver(size).observe(document.body);size()})<\/script>`;
 const head=`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:"><style>:root{${css};--tg-color-scheme:${theme};color-scheme:${theme}}@media(prefers-color-scheme:dark){:root{color-scheme:${theme}}}</style>`;
 frame.srcdoc=html.replace('<head>','<head>'+head+sdk);
 document.getElementById('trace').textContent='Выбери действие в карточке.';
}
function outcome(action){
 const next={...current,revision:current.revision+1,terminal:true};
 if(action==='done'){next.heading='Одним делом меньше!';next.status='Задача завершена';next.summary='Можно выдохнуть — это дело уже позади';next.rows=[['Статус','Готово']];next.note=''}
 else if(action==='working'){next.heading='Оставил в работе';next.status='Статус сохранён';next.summary='Можно сосредоточиться на этом деле';next.note=''}
 else if(action==='later'){next.heading='Напомню через час';next.status='Напоминание сохранено';next.summary='Срок задачи остался прежним';next.rows=[['Напомнить','Сегодня, 16:20'],['Срок','Сегодня, 14:20']];next.note=''}
 else if(action==='a'){next.heading='Задача записана';next.status='Срок и напоминание сохранены';next.summary='Начать собираться — в 13:00, пара — в 14:20';next.note=''}
 else if(action==='r'){next.heading='Не добавлял задачу';next.status='Предложение отклонено';next.summary='Можно уточнить мысль новым сообщением';next.note=''}
 return next;
}
window.addEventListener('message',async event=>{
 if(event.source!==frame.contentWindow||!event.data)return;
 if(event.data.type==='widget-size'){frame.style.height=Math.min(600,Math.max(80,event.data.height))+'px';return}
 if(event.data.type!=='widget-request')return;
 const {id,code}=event.data,run=generation;
 document.getElementById('trace').textContent='Запрос: '+code;
 await new Promise(resolve=>setTimeout(resolve,650));
 if(run!==generation)return;
 if(fail){frame.contentWindow.postMessage({type:'widget-answer',id,error:'TIMEOUT'},'*');document.getElementById('trace').textContent='Ошибка связи. Действие не подтверждено, кнопки снова доступны.';return}
 const allowed=kind==='proposal'?['a','r']:kind==='checkin'?['done','working','later']:['working','later'];
 const action=String(code).slice(current.key.length+1);
 if(!String(code).startsWith(current.key+':')||!allowed.includes(action))return;
 current=outcome(action);frame.contentWindow.postMessage({type:'widget-answer',id,result:{ok:true,state:current}},'*');
 document.getElementById('trace').textContent='Сохранённый результат → обновление карточки, версия '+current.revision+'.';
});
document.querySelectorAll('[data-kind]').forEach(b=>b.addEventListener('click',()=>{kind=b.dataset.kind;paint()}));
document.querySelectorAll('[data-theme]').forEach(b=>b.addEventListener('click',()=>{theme=b.dataset.theme;paint(false)}));
document.querySelectorAll('[data-width]').forEach(b=>b.addEventListener('click',()=>{width=Number(b.dataset.width);paint(false)}));
document.getElementById('reset').addEventListener('click',()=>paint());
document.getElementById('fail').addEventListener('click',event=>{fail=!fail;event.currentTarget.setAttribute('aria-pressed',String(fail));event.currentTarget.textContent=fail?'Ошибка включена':'Проверить ошибку';paint()});
Promise.all([fetch('widget.html').then(r=>r.text()),fetch('examples.json').then(r=>r.json())]).then(([source,data])=>{html=source;examples=data;paint()});
