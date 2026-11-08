import net from 'node:net';
import fs from 'node:fs';
const token = process.argv[2];
const port = Number(process.argv[3] || 43150);
const s = net.connect(port, '127.0.0.1');
let buf=''; let id=1; const pending=new Map(); let readyResolve;
const ready = new Promise(r => readyResolve = r);
s.setEncoding('utf8');
const req=(method,params)=>new Promise((res,rej)=>{const i=id++;pending.set(i,{res,rej});s.write(JSON.stringify({type:'request',id:i,method,params})+'\n');});
s.on('connect',()=>s.write(JSON.stringify({type:'hello',token})+'\n'));
s.on('data',(c)=>{buf+=c;let i;while((i=buf.indexOf('\n'))>=0){const line=buf.slice(0,i);buf=buf.slice(i+1);if(!line.trim())continue;const m=JSON.parse(line);if(m.type==='welcome'){console.log('WELCOME', m.state, m.version, 'autoStart='+m.autoStart); if(m.state==='ready')readyResolve();} else if(m.type==='response'){const p=pending.get(m.id);if(p){pending.delete(m.id);m.ok?p.res(m.result):p.rej(Object.assign(new Error(m.error.message),{code:m.error.code}));}} else if(m.type==='event'&&m.event==='harness.status'){console.log('STATUS',m.data.state,m.data.detail); if(m.data.state==='ready')readyResolve();}}});
await new Promise(r=>setTimeout(r,600));
await Promise.race([ready,new Promise(r=>setTimeout(r,45000))]);
try {
  const list = await req('sessions.list',{});
  console.log('SESSIONS', list.sessions.length);
  for (const x of list.sessions.slice(0,6)) console.log(' ', x.id, 'preset='+x.preset, 'native='+x.native, '|', x.title);
  try { await req('session.open',{sessionId:'session-affcaae0-e071-45f6-9878-e8d6b80c6de4'}); console.log('OPEN PTC: UNEXPECTED SUCCESS'); }
  catch(e){ console.log('OPEN PTC ERROR', e.code, e.message); }
  const created = await req('sessions.new',{cwd:'/storage/emulated/0/Projetos', model:'["opencode-go","glm-5.3-flash"]', reasoningEffort:'max'});
  console.log('NEW', created.session.id, 'cwd='+created.session.cwd, 'model='+created.session.model, 'native='+created.session.native);
  console.log('CONFIG', JSON.stringify(created.session.configOptions).slice(0,900));
  const boot = await req('boot.configure',{enabled:true,cwd:'/storage/emulated/0/Projetos'});
  console.log('BOOT', JSON.stringify(boot));
  const web = await req('web.start',{});
  console.log('WEB', JSON.stringify({owned:web.owned, port:web.port, url: String(web.url).replace(/token=[^&]+/,'token=***')}));
  console.log('WEBSTATUS', JSON.stringify(await req('web.status',{})));
  await req('web.stop',{});
  console.log('WEB STOPPED');
  await req('session.close',{sessionId:created.session.id});
  await req('bridge.shutdown',{});
} catch(e) { console.log('ERR', e.code, e.message); }
setTimeout(()=>process.exit(0),600);
