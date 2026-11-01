package dev.romirom.tivibro.remote

import dev.romirom.tivibro.L10n
import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import org.json.JSONObject

/**
 * The phone remote: one self-contained page (no CDNs, no build step), in the app's language, with two tabs.
 *
 * «Пошук»: a search box with live results in two sections, channels and programmes; with an empty box, now and next on
 * the favourites. «Канали»: the favourites, then the playlist's groups (what the user hid is not there); a group lists
 * its channels a page at a time (a provider group can hold a thousand), each with what is on now. Tapping a channel
 * tunes it live on the TV; its › opens the channel's schedule, a day at a time, back as far as its archive goes and on
 * to tomorrow. A programme in the archive plays from the archive; the one on now asks «live or from the start»;
 * upcoming ones are dimmed. A strip at the bottom shows what the TV plays, with pause and channel ±.
 *
 * Where the page is lives in the address's #fragment (#groups, #g/<group>, #s/<channel>/<day>), so the phone's back
 * button walks back through it and a reload stays put. It works under any of the token forms ([Remote]): `/<token>`
 * (what the QR code opens) or `/?t=<token>`.
 */
object RemotePage {
    fun html(): String {
        val s = JSONObject()
        for ((k, id) in listOf(
            "search" to R.string.rp_search, "channels" to R.string.rp_channels, "programmes" to R.string.rp_programmes,
            "favs" to R.string.rp_favourites, "now" to R.string.rp_now, "archive" to R.string.rp_archive,
            "upcoming" to R.string.rp_upcoming, "live" to R.string.rp_live, "fromStart" to R.string.rp_from_start,
            "sent" to R.string.rp_sent, "offline" to R.string.rp_offline, "nothing" to R.string.rp_nothing,
            "onTv" to R.string.rp_on_tv, "indexing" to R.string.rp_indexing, "noPlaylist" to R.string.rp_no_playlist,
            "notStarted" to R.string.rp_not_started, "cancel" to R.string.rp_cancel, "tabSearch" to R.string.menu_search,
            "groups" to R.string.rp_groups, "schedule" to R.string.rp_schedule, "noProgs" to R.string.rp_no_programmes,
        )) s.put(k, str(id))
        return PAGE.replace("{lang}", L10n.locale.language).replace("{S}", s.toString().replace("</", "<\\/"))
    }

    private val PAGE = """
<!doctype html><html lang="{lang}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0a0b0e"><meta name="apple-mobile-web-app-capable" content="yes">
<title>TiViBro</title>
<style>
:root{--bg:#0a0b0e;--t1:#f5f5f7;--t2:#a3a3ab;--t3:#6b6b73;--glass:rgba(255,255,255,.06);--hair:rgba(255,255,255,.09);--live:#ff453a;--ok:#30d158;--arc:#64d2ff}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:var(--bg);color:var(--t1);font:16px/1.35 -apple-system,BlinkMacSystemFont,"SF Pro Text",Roboto,sans-serif}
body{background:radial-gradient(120% 60% at 50% -10%,#1d2233 0%,var(--bg) 60%) fixed;min-height:100vh;padding-bottom:120px}
header{position:sticky;top:0;z-index:5;padding:calc(12px + env(safe-area-inset-top)) 16px 12px;background:rgba(10,11,14,.72);backdrop-filter:blur(24px) saturate(1.6);-webkit-backdrop-filter:blur(24px) saturate(1.6);border-bottom:1px solid var(--hair)}
.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 4px}
.brand{font-weight:700;letter-spacing:.2px;font-size:15px;color:var(--t1)}
.tabs{display:flex;padding:3px;border-radius:12px;background:rgba(255,255,255,.08);border:1px solid var(--hair)}
.tabs button{border:0;background:none;color:var(--t2);font:600 14px -apple-system,Roboto,sans-serif;padding:7px 14px;border-radius:9px}
.tabs button.on{background:rgba(255,255,255,.16);color:var(--t1)}
.sb{display:flex;align-items:center;gap:10px;height:46px;padding:0 14px;margin-top:12px;border-radius:14px;background:rgba(255,255,255,.09);border:1px solid var(--hair)}
.sb svg{flex:none;opacity:.6}
.sb input{flex:1;min-width:0;border:0;outline:0;background:none;color:var(--t1);font:17px -apple-system,Roboto,sans-serif}
.sb input::placeholder{color:var(--t3)}
.x{border:0;background:rgba(255,255,255,.18);color:var(--bg);width:20px;height:20px;border-radius:10px;font-size:13px;line-height:20px;display:none}
.nav{display:flex;align-items:center;gap:10px;margin-top:12px;min-height:40px}
.back{flex:none;border:0;background:rgba(255,255,255,.1);color:var(--t1);height:36px;padding:0 12px 0 10px;border-radius:18px;font:600 14px -apple-system,Roboto,sans-serif}
.nav .t{flex:1}
.days{display:flex;gap:8px;overflow-x:auto;margin:12px -16px 0;padding:0 16px;scrollbar-width:none}.days::-webkit-scrollbar{display:none}
.days button{flex:none;border:1px solid var(--hair);background:rgba(255,255,255,.06);color:var(--t2);border-radius:12px;padding:7px 12px;font:600 13.5px -apple-system,Roboto,sans-serif}
.days button.on{background:var(--t1);color:#0a0b0e;border-color:var(--t1)}
main{padding:6px 12px}
h2{font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.8px;color:var(--t3);margin:20px 6px 8px}
.list{border-radius:18px;background:var(--glass);border:1px solid var(--hair);overflow:hidden;margin-top:12px}
h2+.list{margin-top:0}
.row{display:flex;align-items:center;gap:12px;padding:10px 12px;min-height:64px;border-top:1px solid var(--hair);cursor:pointer;transition:background .15s}
.row:first-child{border-top:0}
.row:active{background:rgba(255,255,255,.08)}
.row.off{opacity:.42;cursor:default}
.row.cur{background:rgba(255,255,255,.07)}
.logo{flex:none;width:52px;height:38px;border-radius:9px;background:rgba(255,255,255,.07) center/contain no-repeat;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;color:var(--t2)}
.mid{flex:1;min-width:0}
.t{font-size:16px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.s{font-size:13.5px;color:var(--t2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.bar{height:3px;border-radius:2px;background:rgba(255,255,255,.12);margin-top:6px;overflow:hidden}.bar i{display:block;height:100%;background:var(--t1);border-radius:2px}
.bd{flex:none;font-size:11px;font-weight:700;letter-spacing:.5px;text-transform:uppercase;padding:4px 8px;border-radius:7px}
.bd.now{background:rgba(255,69,58,.16);color:var(--live)}.bd.archive{background:rgba(100,210,255,.14);color:var(--arc)}.bd.upcoming{background:rgba(255,255,255,.08);color:var(--t2)}
.num{flex:none;color:var(--t3);font-size:13px;font-variant-numeric:tabular-nums}
.tm{flex:none;width:48px;color:var(--t2);font-size:15px;font-weight:600;font-variant-numeric:tabular-nums}
.chev{flex:none;align-self:stretch;margin:-10px -12px -10px 0;width:48px;border:0;border-left:1px solid var(--hair);background:none;color:var(--t2);font-size:22px}
.chev:active{background:rgba(255,255,255,.1)}
.cnt{flex:none;color:var(--t3);font-size:14px;font-variant-numeric:tabular-nums}
.go{flex:none;color:var(--t3);font-size:22px;margin-left:2px}
.star{flex:none;width:38px;height:38px;border-radius:19px;background:rgba(255,214,10,.14);color:#ffd60a;display:flex;align-items:center;justify-content:center;font-size:18px}
.empty{color:var(--t2);text-align:center;padding:40px 20px}
.note{color:var(--t3);font-size:13px;margin:10px 8px}
.more{height:1px}
footer{position:fixed;left:10px;right:10px;bottom:calc(10px + env(safe-area-inset-bottom));z-index:6;display:none;align-items:center;gap:10px;padding:10px 10px 10px 12px;border-radius:22px;background:rgba(28,29,34,.78);backdrop-filter:blur(28px) saturate(1.8);-webkit-backdrop-filter:blur(28px) saturate(1.8);border:1px solid var(--hair);box-shadow:0 12px 40px rgba(0,0,0,.5)}
footer .mid .s{margin-top:1px}
.ctl{flex:none;display:flex;gap:6px}
.ctl button{width:40px;height:40px;border-radius:20px;border:0;background:rgba(255,255,255,.1);color:var(--t1);font-size:16px}
.ctl button:active{background:rgba(255,255,255,.22)}
.ctl .lv{width:auto;padding:0 12px;font-size:12px;font-weight:700;color:var(--live)}
.toast{position:fixed;left:50%;top:calc(110px + env(safe-area-inset-top));transform:translate(-50%,-10px);opacity:0;transition:.25s;z-index:9;background:rgba(245,245,247,.95);color:#0a0b0e;font-weight:600;font-size:14px;padding:10px 16px;border-radius:14px;max-width:88vw;text-align:center;pointer-events:none}
.toast.on{opacity:1;transform:translate(-50%,0)}.toast.err{background:var(--live);color:#fff}
.sheet{position:fixed;inset:0;z-index:10;background:rgba(0,0,0,.5);display:none;align-items:flex-end}
.sheet.on{display:flex}
.sheet .card{width:100%;padding:18px 14px calc(18px + env(safe-area-inset-bottom));background:rgba(28,29,34,.96);border-radius:22px 22px 0 0;border-top:1px solid var(--hair)}
.sheet .card .t{white-space:normal;margin:0 6px 4px}.sheet .card .s{white-space:normal;margin:0 6px 14px}
.sheet button{display:block;width:100%;height:52px;margin-top:8px;border:0;border-radius:14px;font-size:17px;font-weight:600;background:rgba(255,255,255,.1);color:var(--t1)}
.sheet button.pri{background:var(--t1);color:#0a0b0e}
</style></head><body>
<header><div class="top"><div class="brand">TiViBro</div><div class="tabs"><button id="tS"></button><button id="tC"></button></div></div>
<div class="sb" id="sb"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
<input id="q" type="search" autocomplete="off" autocorrect="off" spellcheck="false" enterkeyhint="search"><button class="x" id="x">✕</button></div>
<div class="nav" id="nav" style="display:none"><button class="back" id="bk"></button><div class="t" id="navt"></div></div>
<div class="days" id="days" style="display:none"></div></header>
<main id="m"></main>
<footer id="np"><div class="logo" id="npl"></div><div class="mid"><div class="t" id="npt"></div><div class="s" id="nps"></div></div>
<div class="ctl"><button id="bp" aria-label="prev">▼</button><button id="bpp" aria-label="play/pause">⏯</button><button id="bn" aria-label="next">▲</button><button class="lv" id="blv"></button></div></footer>
<div class="sheet" id="sh"><div class="card"><div class="t" id="sht"></div><div class="s" id="shs"></div>
<button class="pri" id="shl"></button><button id="shf"></button><button id="shc"></button></div></div>
<div class="toast" id="toast"></div>
<script>
const S={S};
const T=new URLSearchParams(location.search).get('t');
const B=T?'':location.pathname.replace(/\/+${'$'}/,'');
const api=p=>B+'/api/'+p+(T?(p.includes('?')?'&':'?')+'t='+encodeURIComponent(T):'');
const Q=id=>document.getElementById(id);
const lang=document.documentElement.lang||'uk';
const esc=s=>String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const hm=t=>new Date(t).toLocaleTimeString(lang,{hour:'2-digit',minute:'2-digit'});
function day(t){const d=new Date(t),n=new Date();const a=Date.UTC(d.getFullYear(),d.getMonth(),d.getDate()),b=Date.UTC(n.getFullYear(),n.getMonth(),n.getDate());const k=Math.round((a-b)/864e5);
 if(Math.abs(k)<=1){try{const r=new Intl.RelativeTimeFormat(lang,{numeric:'auto'}).format(k,'day');return r.charAt(0).toUpperCase()+r.slice(1)}catch(e){}}
 return d.toLocaleDateString(lang,{weekday:'short',day:'numeric',month:'short'})}
function logo(url,name){const ini=esc((name||'').replace(/^[A-Za-z]{2}:\s*/,'').slice(0,3));return url?'<div class="logo" style="background-image:url(&quot;'+esc(url)+'&quot;)"></div>':'<div class="logo">'+ini+'</div>'}
function bar(s,e){const p=Math.max(0,Math.min(1,(Date.now()-s)/(e-s)));return '<div class="bar"><i style="width:'+(p*100).toFixed(1)+'%"></i></div>'}
let tt;function toast(m,err){const t=Q('toast');t.textContent=m;t.className='toast on'+(err?' err':'');clearTimeout(tt);tt=setTimeout(()=>t.className='toast'+(err?' err':''),2600)}
async function get(p){const r=await fetch(api(p),{cache:'no-store'});if(!r.ok)throw r.status;return r.json()}
async function post(p,b){try{const r=await fetch(api(p),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});const j=await r.json();
 if(j.ok===false)toast(j.error,true);else if(p==='play')toast(S.sent);setTimeout(now,900);return j}catch(e){toast(S.offline,true)}}
// every tappable row points into items: what it is and what tapping it does
const items=[];
function add(o){items.push(o);return items.length-1}
const chev=i=>'<button class="chev" data-s="'+i+'" aria-label="'+esc(S.schedule)+'">›</button>';
function chRow(c){const i=add({k:'c',c});const n=c.now;return '<div class="row" data-i="'+i+'">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(c.name)+'</div>'+
 (n?'<div class="s">'+esc(n.title)+'</div>'+bar(n.start,n.end):'<div class="s">'+esc(c.group)+'</div>')+'</div><div class="num">'+c.num+'</div>'+chev(i)+'</div>'}
function prRow(p){const i=add({k:'p',p});const off=p.when==='upcoming';return '<div class="row'+(off?' off':'')+'" data-i="'+i+'">'+logo(p.logo,p.channel)+
 '<div class="mid"><div class="t">'+esc(p.title)+'</div><div class="s">'+esc(p.channel)+' · '+(p.when==='now'?hm(p.start)+'–'+hm(p.end):day(p.start)+', '+hm(p.start))+'</div>'+(p.when==='now'?bar(p.start,p.end):'')+'</div>'+
 '<span class="bd '+p.when+'">'+(p.when==='now'?S.now:p.when==='archive'?S.archive:S.upcoming)+'</span></div>'}
function favRow(c){const ps=c.programmes||[];const n=ps[0],x=ps[1];const i=add({k:'c',c});
 return '<div class="row" data-i="'+i+'">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(n&&n.when==='now'?n.title:c.name)+'</div><div class="s">'+esc(c.name)+(x?' · '+hm(x.start)+' '+esc(x.title):'')+'</div>'+(n&&n.when==='now'?bar(n.start,n.end):'')+'</div><div class="num">'+c.num+'</div>'+chev(i)+'</div>'}
// a programme of a channel's schedule: the time first, the channel is known
function slRow(p,ch){p.channelId=ch.id;p.channel=ch.name;const i=add({k:'p',p});const off=p.when==='upcoming';
 return '<div class="row'+(off?' off':'')+(p.when==='now'?' cur':'')+'" data-i="'+i+'"><div class="tm">'+hm(p.start)+'</div><div class="mid"><div class="t">'+esc(p.title)+'</div>'+
 (p.when==='now'?bar(p.start,p.end):'<div class="s">'+hm(p.start)+'–'+hm(p.end)+'</div>')+'</div>'+(p.when==='upcoming'?'':'<span class="bd '+p.when+'">'+(p.when==='now'?S.now:S.archive)+'</span>')+'</div>'}

// ---------------------------------------------------------------- where the page is: #… (search), #groups, #g/<id>, #s/<channel>/<day>
let view='';let seq=0;let depth=0;
function go(h){depth++;location.hash=h}
function back(fallback){if(depth>0){depth--;history.back()}else location.replace('#'+fallback)}
function parts(){const h=location.hash.slice(1);const i=h.indexOf('/');return i<0?[h]:[h.slice(0,i)].concat(h.slice(i+1).split('/').map(decodeURIComponent))}
function chrome(v,title,backTo){Q('tS').className=v==='search'?'on':'';Q('tC').className=v==='search'?'':'on';Q('sb').style.display=v==='search'?'flex':'none';
 Q('nav').style.display=title==null?'none':'flex';Q('navt').textContent=title||'';Q('days').style.display=v==='s'?'flex':'none';Q('bk').onclick=()=>back(backTo)}
function route(){const p=parts();const v=p[0]==='groups'||p[0]==='g'||p[0]==='s'?p[0]:'search';view=v;items.length=0;window.scrollTo(0,0);
 if(v==='search'){chrome(v,null);return search()}
 if(v==='groups'){chrome(v,null);return groups()}
 if(v==='g'){chrome(v,'','groups');return group(p[1])}
 chrome(v,'','groups');schedule(p[1],+(p[2]||0))}
window.addEventListener('hashchange',route);
Q('tS').onclick=()=>{if(view!=='search'){depth=0;location.replace('#')}};
Q('tC').onclick=()=>{if(view!=='groups'){depth=0;location.replace('#groups')}};

// ---------------------------------------------------------------- search
async function search(){const q=Q('q').value.trim();Q('x').style.display=q?'block':'none';const my=++seq;items.length=0;
 try{if(!q){const w=await get('whatson');if(my!==seq)return;const cs=(w.channels||[]);Q('m').innerHTML=cs.length?'<h2>'+esc(S.favs)+'</h2><div class="list">'+cs.map(favRow).join('')+'</div>':'';return}
  const r=await get('search?q='+encodeURIComponent(q));if(my!==seq)return;let h='';
  if(!r.ready){Q('m').innerHTML='<div class="empty">'+esc(S.noPlaylist)+'</div>';return}
  if(r.channels.length)h+='<h2>'+esc(S.channels)+'</h2><div class="list">'+r.channels.map(chRow).join('')+'</div>';
  if(r.programmes.length)h+='<h2>'+esc(S.programmes)+'</h2><div class="list">'+r.programmes.map(prRow).join('')+'</div>';
  if(!h)h='<div class="empty">'+esc(S.nothing)+'</div>';if(r.indexing)h+='<div class="note">'+esc(S.indexing)+'</div>';Q('m').innerHTML=h}
 catch(e){if(my===seq)Q('m').innerHTML='<div class="empty">'+esc(S.offline)+'</div>'}}
let deb;Q('q').addEventListener('input',()=>{clearTimeout(deb);deb=setTimeout(search,180)});
Q('q').addEventListener('keydown',e=>{if(e.key==='Enter'){e.target.blur()}});
Q('x').onclick=()=>{Q('q').value='';search();Q('q').focus()};

// ---------------------------------------------------------------- channels: groups, a group a page at a time, a channel's schedule
async function groups(){const my=++seq;
 try{const r=await get('groups');if(my!==seq)return;if(r.ok===false){Q('m').innerHTML='<div class="empty">'+esc(S.noPlaylist)+'</div>';return}
  Q('m').innerHTML='<div class="list">'+r.groups.map(g=>{const i=add({k:'g',g});return '<div class="row" data-i="'+i+'">'+(g.id==='*fav'?'<div class="star">★</div>':'')+
   '<div class="mid"><div class="t">'+esc(g.name)+'</div></div><div class="cnt">'+g.count+'</div><div class="go">›</div></div>'}).join('')+'</div>'}
 catch(e){if(my===seq)Q('m').innerHTML='<div class="empty">'+esc(S.offline)+'</div>'}}
let obs;
async function group(id){const my=++seq;Q('bk').textContent='‹ '+S.groups;Q('m').innerHTML='<div class="list" id="gl"></div><div class="more" id="more"></div>';let off=0,total=1,busy=false;
 if(obs)obs.disconnect();
 async function page(){if(busy||off>=total||my!==seq)return;busy=true;
  try{const r=await get('group?id='+encodeURIComponent(id)+'&offset='+off+'&limit=40');if(my!==seq)return;
   if(r.ok===false){Q('m').innerHTML='<div class="empty">'+esc(r.error)+'</div>';return}
   total=r.total;off+=r.channels.length;if(!r.channels.length)total=off;Q('gl').insertAdjacentHTML('beforeend',r.channels.map(chRow).join(''));
   if(!total)Q('m').innerHTML='<div class="empty">'+esc(S.nothing)+'</div>'}
  catch(e){if(my===seq)toast(S.offline,true)}finally{busy=false}
  // the sentinel may still be on screen after a short page: keep going until it is not
  const m=Q('more');if(m&&my===seq&&off<total&&m.getBoundingClientRect().top<innerHeight+600)page()}
 const g=await get('groups').catch(()=>null);if(my!==seq)return;const gg=g&&g.groups&&g.groups.find(x=>x.id===id);Q('navt').textContent=gg?gg.name:id;
 obs=new IntersectionObserver(es=>{if(es.some(e=>e.isIntersecting))page()},{rootMargin:'600px'});obs.observe(Q('more'));page()}
async function schedule(chId,d){const my=++seq;Q('bk').textContent='‹';
 try{const r=await get('schedule?channel='+encodeURIComponent(chId)+'&day='+d);if(my!==seq)return;
  if(r.ok===false){Q('m').innerHTML='<div class="empty">'+esc(r.error)+'</div>';Q('days').innerHTML='';return}
  const ch=r.channel;Q('navt').innerHTML='';Q('navt').insertAdjacentHTML('beforeend','<div style="display:flex;align-items:center;gap:10px">'+logo(ch.logo,ch.name)+'<div class="t">'+esc(ch.name)+'</div></div>');
  Q('days').innerHTML=r.days.map(x=>'<button data-d="'+x.day+'" class="'+(x.day===r.day?'on':'')+'">'+esc(day(x.start+12*3600e3))+'</button>').join('');
  const on=Q('days').querySelector('.on');if(on)on.scrollIntoView({inline:'center',block:'nearest'});
  Q('days').onclick=e=>{const b=e.target.closest('button');if(b)location.replace('#s/'+encodeURIComponent(chId)+'/'+b.dataset.d)};
  let h=r.programmes.length?'<div class="list">'+r.programmes.map(p=>slRow(p,ch)).join('')+'</div>':'<div class="empty">'+esc(S.noProgs)+'</div>';
  if(r.indexing)h+='<div class="note">'+esc(S.indexing)+'</div>';Q('m').innerHTML=h;
  const cur=Q('m').querySelector('.row.cur');if(cur)cur.scrollIntoView({block:'center'})}
 catch(e){if(my===seq)Q('m').innerHTML='<div class="empty">'+esc(S.offline)+'</div>'}}

// ---------------------------------------------------------------- taps
Q('m').addEventListener('click',e=>{const sc=e.target.closest('.chev');if(sc){const it=items[+sc.dataset.s];if(it)go('s/'+encodeURIComponent(it.c.id)+'/0');return}
 const r=e.target.closest('.row');if(!r)return;const it=items[+r.dataset.i];if(!it)return;
 if(it.k==='g')return go('g/'+encodeURIComponent(it.g.id));
 if(it.k==='c')return post('play',{channelId:it.c.id});const p=it.p;
 if(p.when==='upcoming')return toast(S.notStarted+' · '+day(p.start)+', '+hm(p.start));
 if(p.when==='archive')return post('play',{channelId:p.channelId,start:p.start});
 Q('sht').textContent=p.title;Q('shs').textContent=p.channel+' · '+hm(p.start)+'–'+hm(p.end);
 Q('shl').onclick=()=>{close();post('play',{channelId:p.channelId})};Q('shf').onclick=()=>{close();post('play',{channelId:p.channelId,start:p.start,fromStart:true})};Q('sh').className='sheet on'});
function close(){Q('sh').className='sheet'}
Q('sh').onclick=e=>{if(e.target.id==='sh')close()};Q('shc').onclick=close;
Q('shl').textContent=S.live;Q('shf').textContent=S.fromStart;Q('shc').textContent=S.cancel;Q('blv').textContent=S.live;Q('q').placeholder=S.search;
Q('tS').textContent=S.tabSearch;Q('tC').textContent=S.channels;
Q('bp').onclick=()=>post('key',{key:'prev'});Q('bn').onclick=()=>post('key',{key:'next'});Q('bpp').onclick=()=>post('key',{key:'playpause'});Q('blv').onclick=()=>post('key',{key:'live'});
async function now(){try{const n=await get('now');const f=Q('np');if(!n.playing){f.style.display='none';return}f.style.display='flex';
 const c=n.channel,p=n.programme;Q('npl').outerHTML=logo(c.logo,c.name).replace('class="logo"','class="logo" id="npl"');
 Q('npt').textContent=p?p.title:c.name;Q('nps').textContent=S.onTv+' · '+c.name+' · '+(n.mode==='live'?S.live:hm(n.position))+(n.paused?' · ❚❚':'');
 Q('blv').style.display=n.mode==='live'?'none':''}catch(e){}}
route();now();setInterval(now,8000);document.addEventListener('visibilitychange',()=>{if(!document.hidden){now();if(view==='search'&&!Q('q').value)search()}});
</script></body></html>
"""
}
