package dev.romirom.tivibro.remote

import dev.romirom.tivibro.L10n
import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import org.json.JSONObject

/**
 * The phone remote: one self-contained page (no CDNs, no build step), in the app's language. A search box with live
 * results in two sections, channels and programmes; with an empty box, now and next on the favourites. Tapping a
 * channel tunes it live on the TV; a programme in the archive plays from the archive; the one on now asks «live or from
 * the start»; upcoming ones are shown dimmed. A strip at the bottom shows what the TV plays, with pause and channel ±.
 *
 * It works under any of the token forms ([Remote]): `/<token>` (what the QR code opens) or `/?t=<token>`.
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
            "notStarted" to R.string.rp_not_started, "cancel" to R.string.rp_cancel,
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
header{position:sticky;top:0;z-index:5;padding:calc(14px + env(safe-area-inset-top)) 16px 12px;background:rgba(10,11,14,.72);backdrop-filter:blur(24px) saturate(1.6);-webkit-backdrop-filter:blur(24px) saturate(1.6);border-bottom:1px solid var(--hair)}
.brand{font-weight:700;letter-spacing:.2px;font-size:15px;color:var(--t2);margin:0 4px 10px}
.brand b{color:var(--t1)}
.sb{display:flex;align-items:center;gap:10px;height:46px;padding:0 14px;border-radius:14px;background:rgba(255,255,255,.09);border:1px solid var(--hair)}
.sb svg{flex:none;opacity:.6}
.sb input{flex:1;min-width:0;border:0;outline:0;background:none;color:var(--t1);font:17px -apple-system,Roboto,sans-serif}
.sb input::placeholder{color:var(--t3)}
.x{border:0;background:rgba(255,255,255,.18);color:var(--bg);width:20px;height:20px;border-radius:10px;font-size:13px;line-height:20px;display:none}
main{padding:6px 12px}
h2{font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.8px;color:var(--t3);margin:20px 6px 8px}
.list{border-radius:18px;background:var(--glass);border:1px solid var(--hair);overflow:hidden}
.row{display:flex;align-items:center;gap:12px;padding:10px 12px;min-height:64px;border-top:1px solid var(--hair);cursor:pointer;transition:background .15s}
.row:first-child{border-top:0}
.row:active{background:rgba(255,255,255,.08)}
.row.off{opacity:.42;cursor:default}
.logo{flex:none;width:52px;height:38px;border-radius:9px;background:rgba(255,255,255,.07) center/contain no-repeat;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;color:var(--t2)}
.mid{flex:1;min-width:0}
.t{font-size:16px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.s{font-size:13.5px;color:var(--t2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.bar{height:3px;border-radius:2px;background:rgba(255,255,255,.12);margin-top:6px;overflow:hidden}.bar i{display:block;height:100%;background:var(--t1);border-radius:2px}
.bd{flex:none;font-size:11px;font-weight:700;letter-spacing:.5px;text-transform:uppercase;padding:4px 8px;border-radius:7px}
.bd.now{background:rgba(255,69,58,.16);color:var(--live)}.bd.archive{background:rgba(100,210,255,.14);color:var(--arc)}.bd.upcoming{background:rgba(255,255,255,.08);color:var(--t2)}
.num{flex:none;color:var(--t3);font-size:13px;font-variant-numeric:tabular-nums}
.empty{color:var(--t2);text-align:center;padding:40px 20px}
.note{color:var(--t3);font-size:13px;margin:10px 8px}
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
<header><div class="brand"><b>TiViBro</b></div>
<div class="sb"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
<input id="q" type="search" autocomplete="off" autocorrect="off" spellcheck="false" enterkeyhint="search"><button class="x" id="x">✕</button></div></header>
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
const ${'$'}=id=>document.getElementById(id);
const lang=document.documentElement.lang||'uk';
const esc=s=>String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const hm=t=>new Date(t).toLocaleTimeString(lang,{hour:'2-digit',minute:'2-digit'});
function day(t){const d=new Date(t),n=new Date();const a=Date.UTC(d.getFullYear(),d.getMonth(),d.getDate()),b=Date.UTC(n.getFullYear(),n.getMonth(),n.getDate());const k=Math.round((a-b)/864e5);
 if(Math.abs(k)<=1){try{const r=new Intl.RelativeTimeFormat(lang,{numeric:'auto'}).format(k,'day');return r.charAt(0).toUpperCase()+r.slice(1)}catch(e){}}
 return d.toLocaleDateString(lang,{weekday:'short',day:'numeric',month:'short'})}
function logo(url,name){const ini=esc((name||'').replace(/^[A-Za-z]{2}:\s*/,'').slice(0,3));return url?'<div class="logo" style="background-image:url(&quot;'+esc(url)+'&quot;)"></div>':'<div class="logo">'+ini+'</div>'}
function bar(s,e){const p=Math.max(0,Math.min(1,(Date.now()-s)/(e-s)));return '<div class="bar"><i style="width:'+(p*100).toFixed(1)+'%"></i></div>'}
let tt;function toast(m,err){const t=${'$'}('toast');t.textContent=m;t.className='toast on'+(err?' err':'');clearTimeout(tt);tt=setTimeout(()=>t.className='toast'+(err?' err':''),2600)}
async function get(p){const r=await fetch(api(p),{cache:'no-store'});if(!r.ok)throw r.status;return r.json()}
async function post(p,b){try{const r=await fetch(api(p),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});const j=await r.json();
 if(j.ok===false)toast(j.error,true);else if(p==='play')toast(S.sent);setTimeout(now,900);return j}catch(e){toast(S.offline,true)}}
const items=[];
function chRow(c){items.push({k:'c',c});const n=c.now;return '<div class="row" data-i="'+(items.length-1)+'">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(c.name)+'</div>'+
 (n?'<div class="s">'+esc(n.title)+'</div>'+bar(n.start,n.end):'<div class="s">'+esc(c.group)+'</div>')+'</div><div class="num">'+c.num+'</div></div>'}
function prRow(p){items.push({k:'p',p});const off=p.when==='upcoming';return '<div class="row'+(off?' off':'')+'" data-i="'+(items.length-1)+'">'+logo(p.logo,p.channel)+
 '<div class="mid"><div class="t">'+esc(p.title)+'</div><div class="s">'+esc(p.channel)+' · '+(p.when==='now'?hm(p.start)+'–'+hm(p.end):day(p.start)+', '+hm(p.start))+'</div>'+(p.when==='now'?bar(p.start,p.end):'')+'</div>'+
 '<span class="bd '+p.when+'">'+(p.when==='now'?S.now:p.when==='archive'?S.archive:S.upcoming)+'</span></div>'}
function favRow(c){const ps=c.programmes||[];const n=ps[0],x=ps[1];const o={k:'c',c};items.push(o);
 return '<div class="row" data-i="'+(items.length-1)+'">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(n&&n.when==='now'?n.title:c.name)+'</div><div class="s">'+esc(c.name)+(x?' · '+hm(x.start)+' '+esc(x.title):'')+'</div>'+(n&&n.when==='now'?bar(n.start,n.end):'')+'</div><div class="num">'+c.num+'</div></div>'}
let seq=0;
async function search(){const q=${'$'}('q').value.trim();${'$'}('x').style.display=q?'block':'none';const my=++seq;items.length=0;
 try{if(!q){const w=await get('whatson');if(my!==seq)return;const cs=(w.channels||[]);${'$'}('m').innerHTML=cs.length?'<h2>'+esc(S.favs)+'</h2><div class="list">'+cs.map(favRow).join('')+'</div>':'';return}
  const r=await get('search?q='+encodeURIComponent(q));if(my!==seq)return;let h='';
  if(!r.ready){${'$'}('m').innerHTML='<div class="empty">'+esc(S.noPlaylist)+'</div>';return}
  if(r.channels.length)h+='<h2>'+esc(S.channels)+'</h2><div class="list">'+r.channels.map(chRow).join('')+'</div>';
  if(r.programmes.length)h+='<h2>'+esc(S.programmes)+'</h2><div class="list">'+r.programmes.map(prRow).join('')+'</div>';
  if(!h)h='<div class="empty">'+esc(S.nothing)+'</div>';if(r.indexing)h+='<div class="note">'+esc(S.indexing)+'</div>';${'$'}('m').innerHTML=h}
 catch(e){if(my===seq)${'$'}('m').innerHTML='<div class="empty">'+esc(S.offline)+'</div>'}}
let deb;${'$'}('q').addEventListener('input',()=>{clearTimeout(deb);deb=setTimeout(search,180)});
${'$'}('q').addEventListener('keydown',e=>{if(e.key==='Enter'){e.target.blur()}});
${'$'}('x').onclick=()=>{${'$'}('q').value='';search();${'$'}('q').focus()};
${'$'}('m').addEventListener('click',e=>{const r=e.target.closest('.row');if(!r)return;const it=items[+r.dataset.i];if(!it)return;
 if(it.k==='c')return post('play',{channelId:it.c.id});const p=it.p;
 if(p.when==='upcoming')return toast(S.notStarted+' · '+day(p.start)+', '+hm(p.start));
 if(p.when==='archive')return post('play',{channelId:p.channelId,start:p.start});
 ${'$'}('sht').textContent=p.title;${'$'}('shs').textContent=p.channel+' · '+hm(p.start)+'–'+hm(p.end);
 ${'$'}('shl').onclick=()=>{close();post('play',{channelId:p.channelId})};${'$'}('shf').onclick=()=>{close();post('play',{channelId:p.channelId,start:p.start,fromStart:true})};${'$'}('sh').className='sheet on'});
function close(){${'$'}('sh').className='sheet'}
${'$'}('sh').onclick=e=>{if(e.target.id==='sh')close()};${'$'}('shc').onclick=close;
${'$'}('shl').textContent=S.live;${'$'}('shf').textContent=S.fromStart;${'$'}('shc').textContent=S.cancel;${'$'}('blv').textContent=S.live;${'$'}('q').placeholder=S.search;
${'$'}('bp').onclick=()=>post('key',{key:'prev'});${'$'}('bn').onclick=()=>post('key',{key:'next'});${'$'}('bpp').onclick=()=>post('key',{key:'playpause'});${'$'}('blv').onclick=()=>post('key',{key:'live'});
async function now(){try{const n=await get('now');const f=${'$'}('np');if(!n.playing){f.style.display='none';return}f.style.display='flex';
 const c=n.channel,p=n.programme;${'$'}('npl').outerHTML=logo(c.logo,c.name).replace('class="logo"','class="logo" id="npl"');
 ${'$'}('npt').textContent=p?p.title:c.name;${'$'}('nps').textContent=S.onTv+' · '+c.name+' · '+(n.mode==='live'?S.live:hm(n.position))+(n.paused?' · ❚❚':'');
 ${'$'}('blv').style.display=n.mode==='live'?'none':''}catch(e){}}
search();now();setInterval(now,8000);document.addEventListener('visibilitychange',()=>{if(!document.hidden){now();if(!${'$'}('q').value)search()}});
</script></body></html>
"""
}
