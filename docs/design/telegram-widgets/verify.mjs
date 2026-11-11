const sandbox = process.argv[2];
if(!sandbox)throw Error('Pass the path to Telegram widget-sandbox');
const { runWidget } = await import((await import('node:url')).pathToFileURL(sandbox+'/packages/core/scripts/sandbox-node.ts').href);
import fs from 'node:fs';
import assert from 'node:assert/strict';
const base=new URL('./',import.meta.url);
const html=fs.readFileSync(new URL('widget.html',base),'utf8');
const samples=JSON.parse(fs.readFileSync(new URL('examples.json',base),'utf8'));
let passed=0;
for(const theme of ['light','dark']){for(const [kind,value] of Object.entries(samples)){
 const state=value;
 assert.ok(Buffer.byteLength(JSON.stringify(state),'utf8')<=1024);
 for(const action of (kind==='proposal'?['a','r']:['done','working','later']))assert.ok(Buffer.byteLength(state.key+':'+action,'utf8')<=64);
 const options={theme,context:{canQuery:true,backgroundQuery:true,chatType:'private'},data:{chatData:JSON.stringify(state),publicData:null}};
 const result=await runWidget(html,{...options,probes:[`document.getElementById('title').textContent`,`document.querySelectorAll('button').length`]});
 assert.deepEqual(result.diags,[]);assert.equal(result.stopped,undefined);assert.deepEqual(result.logs,[]);assert.equal(result.requests.length,0);
 assert.ok(result.size.raw<=32768&&result.size.zlib<=4096);assert.equal(result.probes[0],state.title);passed++;
 const button=result.renderOps.find(op=>op.op==='el'&&op.tag==='button').id;
 const saved={...state,revision:2,terminal:true,status:'Изменения сохранены.',heading:'Готово.'};
 const completed=await runWidget(html,{...options,events:[{type:'click',targetId:button,gesture:1}],onRequest:()=>({result:JSON.stringify({ok:true,state:saved})}),
  script:[{data:{chatData:JSON.stringify(state),publicData:null}}],probes:[`document.getElementById('heading').textContent`,`document.querySelectorAll('button').length`]});
 assert.equal(completed.requests.length,1);assert.equal(completed.requests[0].verdict,'foreground');assert.equal(completed.probes[0],'Готово.');assert.equal(completed.probes[1],'0');assert.deepEqual(completed.diags,[]);passed++;
 const failed=await runWidget(html,{...options,events:[{type:'click',targetId:button,gesture:1}],onRequest:()=>{throw 'TIMEOUT'},
  probes:[`document.querySelector('button').disabled`,`document.getElementById('result').textContent`]});
 assert.equal(failed.requests.length,1);assert.equal(failed.probes[0],'false');assert.match(failed.probes[1],/задержался/);assert.deepEqual(failed.diags,[]);passed++;
 console.log('PASS',kind,theme,'raw',result.size.raw,'zlib9',result.size.zlib);
}}
const adversarial={...samples.checkin,title:'<img src="https://example.invalid/a" onerror="alert(1)">'};
const escaped=await runWidget(html,{context:{canQuery:true},data:{chatData:JSON.stringify(adversarial),publicData:null},probes:[`document.querySelectorAll('img').length`,`document.getElementById('title').textContent`]});
assert.equal(escaped.probes[0],'0');assert.equal(escaped.probes[1],adversarial.title);assert.equal(escaped.requests.length,0);passed++;
console.log('TOTAL',passed,'sandbox cases passed');
const paged={...samples.proposal,key:'w:12345678-1234-1234-1234-123456789012:0',revision:0,
 page:0,total:3,selected:2,accepted:true,fingerprint:'0123456789abcdef'};
const loaded=await runWidget(html,{context:{canQuery:true,chatType:'private'},data:{chatData:JSON.stringify(paged),publicData:null},
 probes:[`document.getElementById('paging').textContent`,`document.getElementById('actions').textContent`]});
assert.match(loaded.probes[0],/1 из 3/);assert.match(loaded.probes[1],/Применить: 2/);assert.equal(loaded.requests.length,0);passed++;
const nextButton=loaded.renderOps.filter(op=>op.op==='el'&&op.tag==='button')[2].id;
const nextState={...paged,page:1,revision:1,key:paged.key.replace(':0',':1'),title:'Вторая задача'};
const paginated=await runWidget(html,{context:{canQuery:true,chatType:'private'},data:{chatData:JSON.stringify(paged),publicData:null},
 events:[{type:'click',targetId:nextButton,gesture:1}],onRequest:()=>({result:JSON.stringify({ok:true,state:nextState})}),
 probes:[`document.getElementById('title').textContent`,`document.getElementById('paging').textContent`]});
assert.equal(paginated.requests.length,1);assert.equal(paginated.probes[0],'Вторая задача');assert.match(paginated.probes[1],/2 из 3/);assert.deepEqual(paginated.diags,[]);passed++;
const none=await runWidget(html,{context:{canQuery:true,chatType:'private'},data:{chatData:JSON.stringify({...paged,selected:0,accepted:false}),publicData:null},
 probes:[`document.querySelector('#actions button').disabled`]});
assert.equal(none.probes[0],'true');assert.equal(none.requests.length,0);passed++;
const returning=await runWidget(html,{context:{canQuery:true,chatType:'private'},data:{chatData:JSON.stringify({kind:'return',key:paged.key,revision:0,title:'Расскажи о новых делах',rows:[]}),publicData:null},
 probes:[`document.querySelectorAll('button').length`,`document.getElementById('note').textContent`]});
assert.equal(returning.probes[0],'1');assert.equal(returning.probes[1],'');assert.equal(returning.requests.length,0);assert.deepEqual(returning.diags,[]);passed++;
console.log('TOTAL including production states',passed,'sandbox cases passed');

const receipt={kind:'receipt',key:paged.key,revision:0,title:'Провести пару',heading:'Одним делом меньше!',
 rows:[],page:0,total:2,undoAvailable:true,taskId:'12345678-1234-1234-1234-123456789012'};
for(const theme of ['light','dark']) {
 const opts={theme,context:{canQuery:true,chatType:'private'},data:{chatData:JSON.stringify(receipt),publicData:null}};
 const initial=await runWidget(html,{...opts,probes:[`document.getElementById('heading').textContent`,`document.getElementById('actions').textContent`,`document.getElementById('paging').textContent`]});
 assert.equal(initial.probes[0],receipt.heading);assert.match(initial.probes[1],/Отменить/);assert.match(initial.probes[2],/1 из 2/);
 assert.equal(initial.requests.length,0);assert.deepEqual(initial.diags,[]);passed++;
 const undo=initial.renderOps.find(op=>op.op==='el'&&op.tag==='button').id;
 const undone={...receipt,key:receipt.key.replace(':0',':1'),revision:1,heading:'Действие отменено',undoAvailable:false,taskId:null};
 const changed=await runWidget(html,{...opts,events:[{type:'click',targetId:undo,gesture:1}],onRequest:()=>({result:JSON.stringify({ok:true,state:undone})}),
 probes:[`document.getElementById('heading').textContent`,`document.getElementById('actions').textContent`]});
 assert.equal(changed.requests.length,1);assert.equal(changed.requests[0].verdict,'foreground');
 assert.equal(changed.probes[0],'Действие отменено');assert.doesNotMatch(changed.probes[1],/Отменить/);assert.deepEqual(changed.diags,[]);passed++;
}
console.log('TOTAL including receipts',passed,'sandbox cases passed');
