'use strict';
const $ = id => document.getElementById(id);
const message = text => { $('status').textContent = text; };
const make = (tag,text) => Object.assign(document.createElement(tag),{textContent:text});
const uid = () => crypto.randomUUID();
const database = new Promise((resolve,reject) => {
  const req = indexedDB.open('role-reading-room',1);
  req.onupgradeneeded = () => { for(const name of ['shelf','texts','notes']) req.result.createObjectStore(name,{keyPath:'id'}); };
  req.onsuccess = () => resolve(req.result);
  req.onerror = () => reject(req.error);
});
async function transact(names,mode,action) {
  const db = await database;
  return new Promise((resolve,reject) => {
    const tx = db.transaction(names,mode);
    const result = action(key => tx.objectStore(key));
    tx.oncomplete = () => resolve(result?.result);
    tx.onabort = () => reject(tx.error);
  });
}
const all = key => transact([key],'readonly',s => s(key).getAll());
const put = (key,row) => transact([key],'readwrite',s => s(key).put(row));
let book, pages=[], notes=[], anchor, roleList=[], currentRole='', busy=false;
const pending = new Map();
window.showRoles = (rows,current) => {
  roleList=rows; currentRole=current;
  $('roles').replaceChildren(...rows.map(r => new Option(r.name || '未命名角色',r.id)));
  $('roles').value=book?.role || current;
};
function splitText(text,chapter,result) {
  for(const p of text.split(/\n+/).map(x=>x.trim()).filter(Boolean)) {
    for(let i=0;i<p.length;i+=1800) result.push({text:p.slice(i,i+1800),chapter});
  }
}
async function parseFile(file) {
  if(file.size>48*1024*1024) throw Error('请选择小于 48 MB 的书');
  const pages=[]; let title=file.name.replace(/\.(txt|epub)$/i,''),cover='';
  if(/\.txt$/i.test(file.name)) {
    let chapter='正文';
    for(const line of (await file.text()).split(/\r?\n/)) {
      if(/^第.{1,12}[章回卷]|^chapter\s+\d/i.test(line.trim()) && line.length<90) chapter=line.trim();
      splitText(line,chapter,pages);
    }
  } else if(/\.epub$/i.test(file.name)) {
    const zip=await JSZip.loadAsync(file);
    let expandedBytes=0;
    const xml=async path=>{
      const entry=zip.file(path); if(!entry) throw Error('EPUB 缺少 '+path);
      expandedBytes+=entry._data.uncompressedSize;
      if(entry._data.uncompressedSize>8*1024*1024 || expandedBytes>64*1024*1024) throw Error('电子书解压后过大');
      const doc=new DOMParser().parseFromString(await entry.async('string'),'application/xml');
      if(doc.querySelector('parsererror')) throw Error('EPUB 文档格式有误');
      return doc;
    };
    const container=await xml('META-INF/container.xml');
    const opf=container.querySelector('rootfile')?.getAttribute('full-path');
    const meta=await xml(opf);
    title=meta.getElementsByTagNameNS('*','title')[0]?.textContent || title;
    const items=new Map([...meta.querySelectorAll('manifest > item')].map(i=>[i.id,i]));
    function pathOf(i){return decodeURIComponent(new URL(i.getAttribute('href'),new URL(opf,'https://book.invalid/')).pathname.slice(1));}
    const art=[...items.values()].find(i=>i.getAttribute('properties')?.includes('cover-image')) || items.get(meta.querySelector('meta[name="cover"]')?.getAttribute('content'));
    if(art && /^image\/(png|jpeg|webp)$/.test(art.getAttribute('media-type'))) {
      const entry=zip.file(pathOf(art)); if(entry && entry._data.uncompressedSize<2e6) cover='data:'+art.getAttribute('media-type')+';base64,'+await entry.async('base64');
    }
    for(const ref of meta.querySelectorAll('spine > itemref')) {
      const item=items.get(ref.getAttribute('idref')); if(!item?.getAttribute('media-type')?.includes('html')) continue;
      const doc=await xml(pathOf(item)),body=doc.querySelector('body'); if(!body) continue;
      body.querySelectorAll('script,style').forEach(n=>n.remove());
      const chapter=(body.querySelector('h1,h2,h3')?.textContent || doc.querySelector('title')?.textContent || '章节 '+(1+[...meta.querySelectorAll('spine > itemref')].indexOf(ref))).trim();
      body.querySelectorAll('p,div,br,h1,h2,h3,li,blockquote').forEach(n=>n.append(doc.createTextNode('\n')));
      splitText(body.textContent,chapter,pages);
    }
  } else throw Error('请选择 EPUB 或 TXT');
  if(!pages.length) throw Error('书中没有可读取的文字');
  return {pages,title,cover};
}
$('file').onchange=async e=>{
  const f=e.target.files[0]; e.target.value=''; if(!f)return;
  try {
    message('正在整理书页…'); const data=await parseFile(f);
    const row={id:uid(),title:data.title,cover:data.cover,pos:0,role:currentRole,auto:false,size:20};
    await transact(['shelf','texts'],'readwrite',s=>{s('shelf').put(row);s('texts').put({id:row.id,pages:data.pages});});
    await openBook(row);
  } catch(e){message(e.message);}
};
async function shelf(){
  book=null; $('reader').hidden=true; $('shelf').hidden=false;
  const rows=await all('shelf'); $('library').replaceChildren();
  if(!rows.length) $('library').append(make('p','把第一本 EPUB 或 TXT 放上书架吧。'));
  for(const row of rows){
    const b=make('button',''); b.className='book';
    if(row.cover){const img=document.createElement('img');img.src=row.cover;b.append(img);}
    b.append(make('strong',row.title),make('small','第 '+(row.pos+1)+' 页')); b.onclick=()=>openBook(row); $('library').append(b);
  }
}
async function openBook(row){
  book=row; const data=await transact(['texts'],'readonly',s=>s('texts').get(row.id));
  pages=data.pages;notes=(await all('notes')).filter(n=>n.book===row.id);
  $('shelf').hidden=true;$('reader').hidden=false;$('title').textContent=row.title;
  $('roles').value=row.role || currentRole; $('auto').checked=row.auto;
  $('toc').replaceChildren(...pages.flatMap((p,i)=>!i||pages[i-1].chapter!==p.chapter?[new Option(p.chapter,i)]:[]));
  message('');render();
}
function render(){
  const p=pages[book.pos];const area=$('passage');area.replaceChildren();area.style.fontSize=book.size+'px';
  const here=notes.filter(n=>n.page===book.pos);
  const cuts=[...new Set([0,p.text.length,...here.flatMap(n=>[n.start,n.end])])].sort((a,b)=>a-b);
  for(let i=1;i<cuts.length;i++){
    const start=cuts[i-1],end=cuts[i],value=p.text.slice(start,end);
    const note=here.find(n=>n.start<=start && n.end>=end);
    if(note){const m=make('mark',value);m.onclick=()=>edit(note);area.append(m);}else area.append(document.createTextNode(value));
  }
  $('threads').replaceChildren(...here.map(n=>{const b=make('button',n.author+' · '+n.text.slice(0,32));b.className='book';b.onclick=()=>edit(n);return b;}));
  $('position').textContent=(book.pos+1)+' / '+pages.length;
  $('prev').disabled=!book.pos;$('next').disabled=book.pos===pages.length-1;
  $('toc').value=[...$('toc').options].reverse().find(o=>Number(o.value)<=book.pos).value;
}
function selection(){
  const sel=getSelection(),base=$('passage'),text=pages[book.pos].text;
  let start=0,end=text.length;
  if(sel.rangeCount && !sel.isCollapsed && base.contains(sel.anchorNode) && base.contains(sel.focusNode)){
    const r=sel.getRangeAt(0),before=r.cloneRange();before.selectNodeContents(base);before.setEnd(r.startContainer,r.startOffset);
    start=before.toString().length;end=start+r.toString().length;
  }
  return {book:book.id,page:book.pos,start,end,thread:uid()};
}
function edit(a){
  anchor={book:a.book,page:a.page,start:a.start,end:a.end,thread:a.thread};$('draft').value='';
  $('quote').textContent=pages[a.page].text.slice(a.start,a.end);
  historyView();$('sheet').showModal();
}
function historyView(){
  $('history').replaceChildren(...notes.filter(n=>n.thread===anchor.thread).sort((a,b)=>a.at-b.at).map(n=>{const el=make('div',n.author+' · '+n.text);el.className='note';return el;}));
}
async function save(a,text,author){
  const n={...a,id:uid(),at:Date.now(),text,author};await put('notes',n);
  if(book?.id===a.book){notes.push(n);render();if($('sheet').open && anchor.thread===a.thread)historyView();}
}
async function ask(a,input){
  if(busy)return;
  const role=roleList.find(r=>r.id===book.role) || roleList.find(r=>r.id===$('roles').value);
  if(!role){message('请选择共读角色');return;}
  busy=true;$('ask').disabled=true;const token=uid();
  try{
    if(input)await save(a,input,'我');
    const quote=pages[a.page].text.slice(a.start,a.end);
    const earlier=notes.filter(n=>n.thread===a.thread).slice(-8).map(n=>n.author+'：'+n.text).join('\n').slice(-4000);
    pending.set(token,{a,role});message('正在等 '+(role.name || 'TA')+' 的批注…');
    CoReader.ask(role.id,JSON.stringify({书名:book.title,章节:pages[a.page].chapter,上文:pages[a.page-1]?.text.slice(-600),本段:pages[a.page].text,划线:quote,讨论:earlier}),token);
  }catch(e){busy=false;$('ask').disabled=false;message(e.message);}
}
window.finishNote=async(token,ok,text)=>{
  const entry=pending.get(token);if(!entry)return;pending.delete(token);
  try{if(!ok)throw Error(text);await save(entry.a,text,entry.role.name || 'TA');message('批注已保存');}
  catch(e){message(e.message);if($('sheet').open)$('history').append(make('p',e.message));}
  finally{busy=false;$('ask').disabled=false;}
};
async function go(i){
  book.pos=i;await put('shelf',book);render();window.scrollTo(0,0);
  if(book && book.pos===i && book.auto&&!busy&&!notes.some(n=>n.page===i&&n.author!=='我'))ask({book:book.id,page:i,start:0,end:pages[i].text.length,thread:uid()},'');
}
$('prev').onclick=()=>go(Math.max(0,book.pos-1));$('next').onclick=()=>go(Math.min(pages.length-1,book.pos+1));$('toc').onchange=e=>go(Number(e.target.value));
$('back').onclick=shelf;$('discuss').onpointerdown=e=>e.preventDefault();$('discuss').onclick=()=>edit(selection());
$('close').onclick=()=>$('sheet').close();$('save').onclick=async()=>{await save(anchor,$('draft').value.trim() || '划线','我');$('sheet').close();};
$('ask').onclick=()=>{const v=$('draft').value.trim();$('draft').value='';ask(anchor,v);};
$('auto').onchange=e=>{book.auto=e.target.checked;put('shelf',book);};
$('roles').onchange=e=>{book.role=e.target.value;put('shelf',book);};
$('font').onclick=()=>{book.size=book.size>=28?16:book.size+2;put('shelf',book);render();};
shelf().catch(e=>message(e.message));CoReader.roles();
