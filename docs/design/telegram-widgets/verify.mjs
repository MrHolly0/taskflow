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
