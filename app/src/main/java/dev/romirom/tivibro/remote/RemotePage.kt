package dev.romirom.tivibro.remote

import dev.romirom.tivibro.L10n
import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone remote: one self-contained page (no CDNs, no build step), in the app's language, with three tabs.
 *
 * «Головна»: «Продовжити перегляд» (tap resumes on the TV where it stopped, × forgets the bookmark), the channels
 * watched last, now and next on the favourites. «Пошук»: a search box with live results, channels and programmes.
 * «Канали»: the favourites (which can be reordered), then the playlist's groups; a group lists its channels a page at a
 * time (a provider group can hold a thousand), each with what is on now; at the end, the switched-off groups and hidden
 * channels, to bring back. Every channel has a ☆ that toggles the favourite at once (optimistically, put back if the TV
 * says no); holding a row opens its menu (live, schedule, favourite, hide). A channel's › opens its schedule a day at a
 * time; tapping a programme opens its details with what can be done with it (watch, resume, from the start, live).
 *
 * The strip at the bottom shows what the TV plays; tapping it opens the player panel: a seek bar over the programme
 * that can be dragged (into the archive too, from live), steps of 30 s and 5 min, previous / next programme, from the
 * start, back to live, channel ± and the audio tracks. Every action answers with the player's new state, so the panel
 * follows at once; otherwise it polls, every 2 s while open, every 8 s while not, and not at all while the page is
 * hidden. The TV's clock is taken from each answer, so a phone whose clock is off still draws the live edge right.
 *
 * The TV's own volume (the system's, what the remote's volume keys change) sits at the top of «Головна» and in the
 * player panel: mute, − and + (held, they repeat), a slider over the TV's steps and the level. Each change shows the
 * TV's volume bar; it is drawn at once and sent latest first (one request in flight), and read again with each poll.
 *
 * When TiViBro is not in front on the TV (the answer says `foreground: false`) a banner says so and taps that would
 * play are refused with that message instead of failing a few seconds later; favourites and the rest still work.
 *
 * Where the page is lives in the address's #fragment (#search, #groups, #g/<group>, #s/<channel>/<day>, #hidden), so
 * the phone's back button walks back through it and a reload stays put. It works under any of the token forms
 * ([Remote]): `/<token>` (what the QR code opens) or `/?t=<token>`. A web app manifest (`manifest.webmanifest`, with
 * the token in its start_url) and an icon make «Add to Home screen» open it full-screen like an app.
 */
object RemotePage {
    fun html(token: String): String {
        val s = JSONObject()
        for ((k, id) in listOf(
            "search" to R.string.rp_search, "channels" to R.string.rp_channels, "programmes" to R.string.rp_programmes,
            "favsNow" to R.string.rp_favourites, "now" to R.string.rp_now, "archive" to R.string.rp_archive,
            "upcoming" to R.string.rp_upcoming, "live" to R.string.rp_live, "fromStart" to R.string.rp_from_start,
            "sent" to R.string.rp_sent, "offline" to R.string.rp_offline, "nothing" to R.string.rp_nothing,
            "onTv" to R.string.rp_on_tv, "indexing" to R.string.rp_indexing, "noPlaylist" to R.string.rp_no_playlist,
            "notStarted" to R.string.rp_not_started, "cancel" to R.string.rp_cancel, "tabSearch" to R.string.menu_search,
            "groups" to R.string.rp_groups, "schedule" to R.string.rp_schedule, "noProgs" to R.string.rp_no_programmes,
            "tabHome" to R.string.menu_home, "cont" to R.string.group_cont, "recent" to R.string.group_recent,
            "favs" to R.string.group_fav, "favAdd" to R.string.fav_add, "favRemove" to R.string.fav_remove,
            "favAdded" to R.string.fav_added, "favRemoved" to R.string.fav_removed, "hide" to R.string.ch_hide,
            "show" to R.string.ch_show, "hidden" to R.string.channel_hidden, "back" to R.string.channel_back,
            "hiddenChannels" to R.string.row_hidden, "hiddenNone" to R.string.hidden_none, "menuGroups" to R.string.menu_groups,
            "forget" to R.string.mark_forget, "forgotten" to R.string.mark_forgotten_toast, "searchEmpty" to R.string.search_empty,
            "audio" to R.string.osd_audio, "paused" to R.string.hint_pause, "watch" to R.string.hint_watch,
            "channel" to R.string.ctx_channel, "noArchive" to R.string.no_archive, "outOfArchive" to R.string.out_of_archive,
            "tvBg" to R.string.rp_tv_background, "order" to R.string.rp_edit_order, "done" to R.string.rp_done,
            "favEmpty" to R.string.rp_fav_empty, "behind" to R.string.rp_behind, "resumeAt" to R.string.rp_resume_at,
            "hideGroup" to R.string.rp_hide_group, "manage" to R.string.rp_manage, "min" to R.string.rp_min, "sec" to R.string.rp_sec,
            "prevProg" to R.string.rp_prev_prog, "nextProg" to R.string.rp_next_prog, "volume" to R.string.rp_volume,
            "mute" to R.string.rp_mute, "unmute" to R.string.rp_unmute, "muted" to R.string.rp_muted,
        )) s.put(k, str(id).replace("%1\$s", "{1}").replace("%1\$d", "{1}"))
        return PAGE.replace("{lang}", L10n.locale.language).replace("{base}", "/$token")
            .replace("{S}", s.toString().replace("</", "<\\/"))
    }

    /** «Add to Home screen»: full-screen, dark, opening on the page with the token in its address. */
    fun manifest(token: String): String = JSONObject()
        .put("name", "TiViBro").put("short_name", "TiViBro").put("id", "/$token/").put("start_url", "/$token/").put("scope", "/$token/")
        .put("display", "standalone").put("orientation", "portrait").put("background_color", "#0a0b0e").put("theme_color", "#0a0b0e")
        .put("icons", JSONArray().put(JSONObject().put("src", "/$token/icon.png").put("sizes", "192x192").put("type", "image/png").put("purpose", "any")))
        .toString().replace("\\/", "/")

    private val PAGE = """
<!doctype html><html lang="{lang}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0a0b0e"><meta name="mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"><meta name="apple-mobile-web-app-title" content="TiViBro">
<link rel="manifest" href="{base}/manifest.webmanifest"><link rel="icon" href="{base}/icon.png"><link rel="apple-touch-icon" href="{base}/icon.png">
<title>TiViBro</title>
<style>
:root{--bg:#0a0b0e;--t1:#f5f5f7;--t2:#a3a3ab;--t3:#6b6b73;--glass:rgba(255,255,255,.06);--hair:rgba(255,255,255,.09);--live:#ff453a;--ok:#30d158;--arc:#64d2ff;--star:#ffd60a}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:var(--bg);color:var(--t1);font:16px/1.35 -apple-system,BlinkMacSystemFont,"SF Pro Text",Roboto,sans-serif}
body{background:radial-gradient(120% 60% at 50% -10%,#1d2233 0%,var(--bg) 60%) fixed;min-height:100vh;padding-bottom:120px;-webkit-user-select:none;user-select:none}
button{font-family:inherit;cursor:pointer}
header{position:sticky;top:0;z-index:5;padding:calc(12px + env(safe-area-inset-top)) 16px 12px;background:rgba(10,11,14,.72);backdrop-filter:blur(24px) saturate(1.6);-webkit-backdrop-filter:blur(24px) saturate(1.6);border-bottom:1px solid var(--hair)}
.top{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:0 2px}
.brand{font-weight:700;letter-spacing:.2px;font-size:15px}
@media(max-width:390px){.brand{display:none}.top{justify-content:center}}
.tabs{display:flex;padding:3px;border-radius:12px;background:rgba(255,255,255,.08);border:1px solid var(--hair)}
.tabs button{border:0;background:none;color:var(--t2);font-weight:600;font-size:14px;padding:7px 13px;border-radius:9px}
.tabs button.on{background:rgba(255,255,255,.16);color:var(--t1)}
.sb{display:flex;align-items:center;gap:10px;height:46px;padding:0 14px;margin-top:12px;border-radius:14px;background:rgba(255,255,255,.09);border:1px solid var(--hair)}
.sb svg{flex:none;opacity:.6}
.sb input{flex:1;min-width:0;border:0;outline:0;background:none;color:var(--t1);font:17px -apple-system,Roboto,sans-serif}
.sb input::placeholder{color:var(--t3)}
.x{border:0;background:rgba(255,255,255,.18);color:var(--bg);width:20px;height:20px;border-radius:10px;font-size:13px;line-height:20px;display:none;padding:0}
.nav{display:flex;align-items:center;gap:10px;margin-top:12px;min-height:40px}
.back{flex:none;border:0;background:rgba(255,255,255,.1);color:var(--t1);height:36px;padding:0 12px 0 10px;border-radius:18px;font-weight:600;font-size:14px}
.nav .t{flex:1}
.pill{flex:none;border:0;background:rgba(255,255,255,.1);color:var(--t1);height:36px;padding:0 14px;border-radius:18px;font-weight:600;font-size:14px}
.pill.on{background:var(--t1);color:var(--bg)}
.days{display:flex;gap:8px;overflow-x:auto;margin:12px -16px 0;padding:0 16px;scrollbar-width:none}.days::-webkit-scrollbar{display:none}
.days button{flex:none;border:1px solid var(--hair);background:rgba(255,255,255,.06);color:var(--t2);border-radius:12px;padding:7px 12px;font-weight:600;font-size:13.5px}
.days button.on{background:var(--t1);color:var(--bg);border-color:var(--t1)}
.bn{display:none;margin-top:12px;padding:10px 14px;border-radius:14px;background:rgba(255,159,10,.14);border:1px solid rgba(255,159,10,.35);color:#ffd60a;font-size:14px;font-weight:600}
@keyframes flash{0%,100%{transform:none}25%{transform:translateX(-6px)}75%{transform:translateX(6px)}}
.bn.flash{animation:flash .3s 2}
.bn.on{display:block}.bn.err{background:rgba(255,69,58,.14);border-color:rgba(255,69,58,.4);color:#ff6961}
main{padding:6px 12px}
h2{font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.8px;color:var(--t3);margin:22px 6px 8px}
.list{border-radius:18px;background:var(--glass);border:1px solid var(--hair);overflow:hidden;margin-top:12px}
h2+.list,h2+.tiles{margin-top:0}
.row{display:flex;align-items:center;gap:12px;padding:10px 12px;min-height:64px;border-top:1px solid var(--hair);cursor:pointer;transition:background .15s}
.row:first-child{border-top:0}
.row:active{background:rgba(255,255,255,.08)}
.row.off{opacity:.42}
.row.cur{background:rgba(255,255,255,.07)}
.logo{flex:none;width:52px;height:38px;border-radius:9px;background:rgba(255,255,255,.07) center/contain no-repeat;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;color:var(--t2)}
.mid{flex:1;min-width:0}
.t{font-size:16px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.s{font-size:13.5px;color:var(--t2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
.bar{height:3px;border-radius:2px;background:rgba(255,255,255,.12);margin-top:6px;overflow:hidden}.bar i{display:block;height:100%;background:var(--t1);border-radius:2px}
.bar.w i{background:var(--arc)}
.bd{flex:none;font-size:11px;font-weight:700;letter-spacing:.5px;text-transform:uppercase;padding:4px 8px;border-radius:7px}
.bd.now,.bd.live{background:rgba(255,69,58,.16);color:var(--live)}.bd.archive{background:rgba(100,210,255,.14);color:var(--arc)}.bd.upcoming,.bd.paused{background:rgba(255,255,255,.1);color:var(--t2)}
.tm{flex:none;width:48px;color:var(--t2);font-size:15px;font-weight:600;font-variant-numeric:tabular-nums}
.ib{flex:none;width:40px;height:40px;margin:-4px 0;border:0;border-radius:20px;background:none;color:var(--t3);font-size:21px;line-height:40px;padding:0}
.ib:active{background:rgba(255,255,255,.12)}
.ib.st.on{color:var(--star)}
.chev{flex:none;align-self:stretch;margin:-10px -12px -10px -4px;width:46px;border:0;border-left:1px solid var(--hair);background:none;color:var(--t2);font-size:22px;padding:0}
.chev:active{background:rgba(255,255,255,.1)}
.cnt{flex:none;color:var(--t3);font-size:14px;font-variant-numeric:tabular-nums}
.go{flex:none;color:var(--t3);font-size:22px;margin-left:2px}
.star{flex:none;width:38px;height:38px;border-radius:19px;background:rgba(255,214,10,.14);color:var(--star);display:flex;align-items:center;justify-content:center;font-size:18px}
.sw{flex:none;width:50px;height:30px;border-radius:15px;background:rgba(255,255,255,.16);position:relative;transition:background .2s;border:0;padding:0}
.sw::after{content:"";position:absolute;top:3px;left:3px;width:24px;height:24px;border-radius:12px;background:#fff;transition:transform .2s}
.sw.on{background:var(--ok)}.sw.on::after{transform:translateX(20px)}
.tiles{display:flex;gap:10px;overflow-x:auto;margin:0 -12px;padding:2px 12px 4px;scrollbar-width:none}.tiles::-webkit-scrollbar{display:none}
.tile{flex:none;width:96px;padding:10px 8px;border-radius:16px;background:var(--glass);border:1px solid var(--hair);text-align:center;cursor:pointer}
.tile .logo{margin:0 auto 8px;width:64px;height:44px}
.tile .t{font-size:12.5px;font-weight:600}
.empty{color:var(--t2);text-align:center;padding:40px 20px}
.note{color:var(--t3);font-size:13px;margin:10px 8px}
.more{height:1px}
footer{position:fixed;left:10px;right:10px;bottom:calc(10px + env(safe-area-inset-bottom));z-index:6;display:none;align-items:center;gap:10px;padding:10px 10px 10px 12px;border-radius:22px;background:rgba(28,29,34,.82);backdrop-filter:blur(28px) saturate(1.8);-webkit-backdrop-filter:blur(28px) saturate(1.8);border:1px solid var(--hair);box-shadow:0 12px 40px rgba(0,0,0,.5);overflow:hidden;cursor:pointer}
footer .pg{position:absolute;left:0;top:0;height:2px;background:var(--t1);opacity:.7}
footer .mid .s{margin-top:1px}
.ctl{flex:none;display:flex;gap:6px}
.ctl button{width:40px;height:40px;border-radius:20px;border:0;background:rgba(255,255,255,.1);color:var(--t1);font-size:16px;padding:0}
.ctl button:active{background:rgba(255,255,255,.22)}
.toast{position:fixed;left:50%;top:calc(110px + env(safe-area-inset-top));transform:translate(-50%,-10px);opacity:0;transition:.25s;z-index:20;background:rgba(245,245,247,.95);color:#0a0b0e;font-weight:600;font-size:14px;padding:10px 16px;border-radius:14px;max-width:88vw;text-align:center;pointer-events:none}
.toast.on{opacity:1;transform:translate(-50%,0)}.toast.err{background:var(--live);color:#fff}
.sheet{position:fixed;inset:0;z-index:10;background:rgba(0,0,0,.5);display:none;align-items:flex-end}
.sheet.on{display:flex}
.card{width:100%;max-height:92vh;overflow-y:auto;padding:10px 14px calc(18px + env(safe-area-inset-bottom));background:#1c1d22;border-radius:22px 22px 0 0;border-top:1px solid var(--hair)}
.grab{width:40px;height:5px;border-radius:3px;background:rgba(255,255,255,.25);margin:0 auto 12px}
.card .t{white-space:normal;margin:0 6px 4px;font-size:18px}.card .s{white-space:normal;margin:0 6px 12px}
.card .d{color:var(--t2);font-size:14.5px;line-height:1.45;margin:0 6px 14px;white-space:pre-line;-webkit-user-select:text;user-select:text}
.card .acts button{display:block;width:100%;height:52px;margin-top:8px;border:0;border-radius:14px;font-size:17px;font-weight:600;background:rgba(255,255,255,.1);color:var(--t1)}
.card .acts button.pri{background:var(--t1);color:var(--bg)}
.card .acts button.red{color:var(--live)}
.ph{display:flex;align-items:center;gap:12px;margin:0 6px 14px}
.ph .mid .t{margin:0;font-size:16px}.ph .mid .s{margin:2px 0 0}
.pt{font-size:21px;font-weight:700;margin:0 6px 4px;line-height:1.25}
.pm{display:flex;align-items:center;gap:8px;margin:0 6px 16px;color:var(--t2);font-size:14px;flex-wrap:wrap}
.sk{position:relative;height:36px;margin:0 6px;touch-action:none;cursor:pointer}
.sk .tr{position:absolute;left:0;right:0;top:15px;height:6px;border-radius:3px;background:rgba(255,255,255,.08)}
.sk .av{position:absolute;top:15px;height:6px;border-radius:3px;background:rgba(255,255,255,.22)}
.sk .fl{position:absolute;left:0;top:15px;height:6px;border-radius:3px;background:var(--t1)}
.sk .th{position:absolute;top:8px;width:20px;height:20px;margin-left:-10px;border-radius:10px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.5)}
.sk.off{opacity:.5;cursor:default}
.sk .bb{position:absolute;bottom:34px;transform:translateX(-50%);background:var(--t1);color:var(--bg);font-weight:700;font-size:14px;padding:4px 8px;border-radius:8px;display:none;font-variant-numeric:tabular-nums;white-space:nowrap}
.sk.drag .bb{display:block}.sk.drag .th{transform:scale(1.25)}
.tl{display:flex;justify-content:space-between;margin:2px 6px 14px;color:var(--t2);font-size:13px;font-variant-numeric:tabular-nums}
.tl b{color:var(--t1);font-weight:600}
.pb{display:flex;justify-content:space-between;align-items:center;gap:4px;margin:0 2px 12px}
.pb button{flex:1;min-width:0;height:48px;border:0;border-radius:14px;background:rgba(255,255,255,.09);color:var(--t1);font-size:13.5px;font-weight:600;padding:0}
.pb button.big{flex:1.4;height:56px;font-size:24px;background:var(--t1);color:var(--bg);border-radius:28px}
.pb button:disabled{opacity:.3}
.pb button:active:not(:disabled){filter:brightness(1.4)}
.chips{display:flex;gap:8px;flex-wrap:wrap;margin:0 6px 14px;align-items:center}
.chips span{color:var(--t3);font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.6px;margin-right:4px}
.chips button{border:1px solid var(--hair);background:rgba(255,255,255,.06);color:var(--t2);border-radius:12px;padding:8px 12px;font-weight:600;font-size:14px}
.chips button.on{background:var(--t1);color:var(--bg);border-color:var(--t1)}
.vol{display:flex;align-items:center;gap:8px}
.vol button{flex:none;width:44px;height:44px;border-radius:22px;border:0;background:rgba(255,255,255,.1);color:var(--t1);font-size:24px;line-height:44px;padding:0;touch-action:manipulation}
.vol button svg{display:block;margin:auto}
.vol button:active{background:rgba(255,255,255,.22)}
.vol.mu button[data-vm]{background:rgba(255,69,58,.16);color:var(--live)}
.vol input{flex:1;min-width:0;height:40px;margin:0 2px;background:none;-webkit-appearance:none;appearance:none}
.vol input::-webkit-slider-runnable-track{height:6px;border-radius:3px;background:linear-gradient(90deg,var(--t1) var(--p,0%),rgba(255,255,255,.12) var(--p,0%))}
.vol input::-webkit-slider-thumb{-webkit-appearance:none;width:22px;height:22px;margin-top:-8px;border-radius:11px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.5)}
.vol input::-moz-range-track{height:6px;border-radius:3px;background:rgba(255,255,255,.12)}
.vol input::-moz-range-progress{height:6px;border-radius:3px;background:var(--t1)}
.vol input::-moz-range-thumb{width:22px;height:22px;border:0;border-radius:11px;background:#fff}
.vol.mu input{opacity:.4}
.vol .n{flex:none;min-width:30px;text-align:right;color:var(--t2);font-size:15px;font-weight:600;font-variant-numeric:tabular-nums}
.vol.off{opacity:.4;pointer-events:none}
</style></head><body>
<header><div class="top"><div class="brand">TiViBro</div><div class="tabs"><button id="tH"></button><button id="tS"></button><button id="tC"></button></div></div>
<div class="sb" id="sb"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
<input id="q" type="search" autocomplete="off" autocorrect="off" spellcheck="false" enterkeyhint="search"><button class="x" id="x">✕</button></div>
<div class="nav" id="nav" style="display:none"><button class="back" id="bk"></button><div class="t" id="navt"></div><div id="nava"></div></div>
<div class="days" id="days" style="display:none"></div><div class="bn" id="bn"></div></header>
<main id="m"></main>
<footer id="np"><div class="pg" id="npg"></div><div class="logo" id="npl"></div><div class="mid"><div class="t" id="npt"></div><div class="s" id="nps"></div></div>
<div class="ctl"><button id="bp" aria-label="prev">▼</button><button id="bpp" aria-label="play/pause">⏯</button><button id="bn2" aria-label="next">▲</button></div></footer>
<div class="sheet" id="sh"><div class="card" id="shc"></div></div>
<div class="sheet" id="ps"><div class="card" id="psc"></div></div>
<div class="toast" id="toast"></div>
<script>
const S={S};
const T=new URLSearchParams(location.search).get('t');
const B=T?'':location.pathname.replace(/\/+${'$'}/,'');
const api=p=>B+'/api/'+p+(T?(p.includes('?')?'&':'?')+'t='+encodeURIComponent(T):'');
const Q=id=>document.getElementById(id);
const lang=document.documentElement.lang||'uk';
const esc=s=>String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const fmt=(s,a)=>String(s).replace('{1}',a);
const buzz=(ms)=>{try{navigator.vibrate&&navigator.vibrate(ms||12)}catch(e){}};
// the TV's clock: every answer carries it, so a phone whose clock is off still draws «now» where the TV has it
let off=0;const nowT=()=>Date.now()+off;
const hm=t=>new Date(t).toLocaleTimeString(lang,{hour:'2-digit',minute:'2-digit'});
const hms=t=>new Date(t).toLocaleTimeString(lang,{hour:'2-digit',minute:'2-digit',second:'2-digit'});
const dur=ms=>{ms=Math.max(0,Math.round(ms/1000));const h=Math.floor(ms/3600),m=Math.floor(ms%3600/60),s=ms%60;return (h?h+':'+String(m).padStart(2,'0'):m)+':'+String(s).padStart(2,'0')};
function day(t){const d=new Date(t),n=new Date(nowT());const a=Date.UTC(d.getFullYear(),d.getMonth(),d.getDate()),b=Date.UTC(n.getFullYear(),n.getMonth(),n.getDate());const k=Math.round((a-b)/864e5);
 if(Math.abs(k)<=1){try{const r=new Intl.RelativeTimeFormat(lang,{numeric:'auto'}).format(k,'day');return r.charAt(0).toUpperCase()+r.slice(1)}catch(e){}}
 return d.toLocaleDateString(lang,{weekday:'short',day:'numeric',month:'short'})}
function logo(url,name,id){const ini=esc((name||'').replace(/^[A-Za-z]{2}:\s*/,'').slice(0,3));const a=id?' id="'+id+'"':'';return url?'<div class="logo"'+a+' style="background-image:url(&quot;'+esc(url)+'&quot;)"></div>':'<div class="logo"'+a+'>'+ini+'</div>'}
function bar(s,e,w,cls){const p=Math.max(0,Math.min(1,((w==null?nowT():w)-s)/(e-s)));return '<div class="bar'+(cls?' '+cls:'')+'"><i style="width:'+(p*100).toFixed(1)+'%"></i></div>'}
let tt;function toast(m,err){const t=Q('toast');t.textContent=m;t.className='toast on'+(err?' err':'');clearTimeout(tt);tt=setTimeout(()=>t.className='toast'+(err?' err':''),2600)}
async function get(p){const r=await fetch(api(p),{cache:'no-store'});if(!r.ok)throw r.status;return r.json()}
// a failure is said in a toast; the answer (or null when the TV did not answer) goes back to the caller
async function post(p,b){try{const r=await fetch(api(p),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});const j=await r.json();
 if(j.now)applyNow(j.now);if(j.ok===false){toast(j.error,true);buzz([30,40,30])}return j}catch(e){toast(S.offline,true);return null}}

// ---------------------------------------------------------------- the TV's state: what plays, whether TiViBro is in front
let np=null,npAt=0,reach=true;
function fg(){return !np||np.foreground!==false}
function banner(){const b=Q('bn');if(!reach){b.textContent=S.offline;b.className='bn on err'}else if(!fg()){b.textContent=S.tvBg;b.className='bn on'}else b.className='bn'}
// what would play: refused at once with the reason while TiViBro is not in front (Android would not let it come up)
function canPlay(){if(fg())return true;const b=open()&&Q('pbn')||Q('bn');b.classList.remove('flash');void b.offsetWidth;b.classList.add('flash');window.scrollTo({top:0,behavior:'smooth'});buzz([30,40,30]);return false}
async function play(b){if(!canPlay())return;buzz();const j=await post('play',b);if(j&&j.ok!==false){toast(S.sent);setTimeout(now,700)}}

// ---------------------------------------------------------------- favourites: one set for the page, every ☆ follows it
const favs=new Set();
function note(c){if(c&&c.id!=null&&c.fav!=null){c.fav?favs.add(c.id):favs.delete(c.id)}}
const star=(c)=>{note(c);const on=favs.has(c.id);return '<button class="ib st'+(on?' on':'')+'" data-f="'+esc(c.id)+'" data-n="'+esc(c.name)+'" aria-label="'+esc(on?S.favRemove:S.favAdd)+'">'+(on?'★':'☆')+'</button>'};
function paintFav(id,on){on?favs.add(id):favs.delete(id);document.querySelectorAll('[data-f]').forEach(b=>{if(b.dataset.f===id){b.classList.toggle('on',on);b.textContent=on?'★':'☆'}})}
async function toggleFav(id,name){const on=!favs.has(id);paintFav(id,on);buzz();const j=await post('fav',{channelId:id,on});
 if(!j||j.ok===false){paintFav(id,!on);return}toast(fmt(on?S.favAdded:S.favRemoved,name))}

// every tappable row points into items: what it is and what tapping it does
const items=[];
function add(o){items.push(o);return items.length-1}
const chev=i=>'<button class="chev" data-s="'+i+'" aria-label="'+esc(S.schedule)+'">›</button>';
function chRow(c){const i=add({k:'c',c});const n=c.now;return '<div class="row" data-i="'+i+'">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(c.name)+'</div>'+
 (n?'<div class="s">'+c.num+' · '+esc(n.title)+'</div>'+bar(n.start,n.end):'<div class="s">'+c.num+' · '+esc(c.group)+'</div>')+'</div>'+star(c)+chev(i)+'</div>'}
function prRow(p){const i=add({k:'p',p});const off=p.when==='upcoming';return '<div class="row'+(off?' off':'')+'" data-i="'+i+'">'+logo(p.logo,p.channel)+
 '<div class="mid"><div class="t">'+esc(p.title)+'</div><div class="s">'+esc(p.channel)+' · '+(p.when==='now'?hm(p.start)+'–'+hm(p.end):day(p.start)+', '+hm(p.start))+'</div>'+(p.when==='now'?bar(p.start,p.end):'')+'</div>'+
 '<span class="bd '+p.when+'">'+(p.when==='now'?S.now:p.when==='archive'?S.archive:S.upcoming)+'</span></div>'}
function favRow(c){const ps=c.programmes||[];const n=ps[0],x=ps[1];const i=add({k:'c',c});
 return '<div class="row" data-i="'+i+'">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(n&&n.when==='now'?n.title:c.name)+'</div><div class="s">'+esc(c.name)+(x?' · '+hm(x.start)+' '+esc(x.title):'')+'</div>'+(n&&n.when==='now'?bar(n.start,n.end):'')+'</div>'+star(c)+chev(i)+'</div>'}
// a programme of a channel's schedule: the time first, the channel is known
function slRow(p,ch){p.channelId=ch.id;p.channel=ch.name;p.logo=ch.logo;const i=add({k:'p',p});const off=p.when==='upcoming';
 return '<div class="row'+(off?' off':'')+(p.when==='now'?' cur':'')+'" data-i="'+i+'"><div class="tm">'+hm(p.start)+'</div><div class="mid"><div class="t">'+esc(p.title)+'</div>'+
 (p.when==='now'?bar(p.start,p.end):'<div class="s">'+hm(p.start)+'–'+hm(p.end)+'</div>')+'</div>'+(p.when==='upcoming'?'':'<span class="bd '+p.when+'">'+(p.when==='now'?S.now:S.archive)+'</span>')+'</div>'}
// a bookmark: tap resumes where it stopped, × forgets it
function bmRow(b){const i=add({k:'b',b});return '<div class="row" data-i="'+i+'">'+logo(b.logo,b.channel)+'<div class="mid"><div class="t">'+esc(b.title)+'</div><div class="s">'+esc(b.channel)+' · '+day(b.start)+', '+hm(b.start)+'</div>'+
 bar(b.start,b.end,b.position,'w')+'</div><button class="ib" data-x="'+i+'" aria-label="'+esc(S.forget)+'">✕</button></div>'}
function tile(c){const i=add({k:'c',c});return '<div class="tile" data-i="'+i+'">'+logo(c.logo,c.name)+'<div class="t">'+esc(c.name)+'</div></div>'}

// ---------------------------------------------------------------- where the page is: # (home), #search, #groups, #g/<id>, #s/<channel>/<day>, #hidden
let view='';let seq=0;let depth=0;
function go(h){depth++;location.hash=h}
function back(fallback){if(depth>0){depth--;history.back()}else location.replace('#'+fallback)}
function parts(){const h=location.hash.slice(1);const i=h.indexOf('/');return i<0?[h]:[h.slice(0,i)].concat(h.slice(i+1).split('/').map(decodeURIComponent))}
const TAB={home:'tH',search:'tS'};
function chrome(v,title,backTo){for(const id of ['tH','tS','tC'])Q(id).className=(TAB[v]||'tC')===id?'on':'';Q('sb').style.display=v==='search'?'flex':'none';
 Q('nav').style.display=title==null?'none':'flex';Q('navt').textContent=title||'';Q('nava').innerHTML='';Q('days').style.display=v==='s'?'flex':'none';Q('bk').textContent='‹ '+S.groups;Q('bk').onclick=()=>back(backTo)}
function route(){const p=parts();const v=['search','groups','g','s','hidden'].includes(p[0])?p[0]:'home';view=v;items.length=0;window.scrollTo(0,0);closeSheet();
 if(v==='home'){chrome(v,null);return home()}
 if(v==='search'){chrome(v,null);return search()}
 if(v==='groups'){chrome(v,null);return groups()}
 if(v==='hidden'){chrome(v,S.manage,'groups');return hidden()}
 if(v==='g'){chrome(v,'','groups');return group(p[1])}
 chrome(v,'','groups');schedule(p[1],+(p[2]||0))}
window.addEventListener('hashchange',route);
Q('tH').onclick=()=>{if(view!=='home'){depth=0;location.replace('#')}};
Q('tS').onclick=()=>{if(view!=='search'){depth=0;location.replace('#search')}else Q('q').focus()};
Q('tC').onclick=()=>{if(view!=='groups'){depth=0;location.replace('#groups')}};
const fail=(my,m)=>{if(my===seq)Q('m').innerHTML='<div class="empty">'+esc(m||S.offline)+'</div>'};

// ---------------------------------------------------------------- home: continue watching, recent channels, favourites now
async function home(){const my=++seq;
 try{const r=await get('home');if(my!==seq)return;items.length=0;if(!r.ready)return fail(my,S.noPlaylist);let h='';
  h+='<div class="list" style="padding:8px 12px 8px 10px">'+volBox()+'</div>';
  if(r.bookmarks.length)h+='<h2>'+esc(S.cont)+'</h2><div class="list">'+r.bookmarks.map(bmRow).join('')+'</div>';
  if(r.recent.length)h+='<h2>'+esc(S.recent)+'</h2><div class="tiles">'+r.recent.map(tile).join('')+'</div>';
  h+='<h2>'+esc(S.favsNow)+'</h2>'+(r.favourites.length?'<div class="list">'+r.favourites.map(favRow).join('')+'</div>':'<div class="note">'+esc(S.favEmpty)+'</div>');
  Q('m').innerHTML=h;paintVol();volGet(true)}catch(e){fail(my)}}

// ---------------------------------------------------------------- search
async function search(){const q=Q('q').value.trim();Q('x').style.display=q?'block':'none';const my=++seq;items.length=0;
 if(!q){Q('m').innerHTML='<div class="empty">'+esc(S.searchEmpty)+'</div>';return}
 try{const r=await get('search?q='+encodeURIComponent(q));if(my!==seq)return;let h='';
  if(!r.ready)return fail(my,S.noPlaylist);
  if(r.channels.length)h+='<h2>'+esc(S.channels)+'</h2><div class="list">'+r.channels.map(chRow).join('')+'</div>';
  if(r.programmes.length)h+='<h2>'+esc(S.programmes)+'</h2><div class="list">'+r.programmes.map(prRow).join('')+'</div>';
  if(!h)h='<div class="empty">'+esc(S.nothing)+'</div>';if(r.indexing)h+='<div class="note">'+esc(S.indexing)+'</div>';Q('m').innerHTML=h}
 catch(e){fail(my)}}
let deb;Q('q').addEventListener('input',()=>{clearTimeout(deb);deb=setTimeout(search,180)});
Q('q').addEventListener('keydown',e=>{if(e.key==='Enter'){e.target.blur()}});
Q('x').onclick=()=>{Q('q').value='';search();Q('q').focus()};

// ---------------------------------------------------------------- channels: groups, a group a page at a time, a channel's schedule, what is hidden
async function groups(){const my=++seq;
 try{const r=await get('groups');if(my!==seq)return;if(r.ok===false)return fail(my,S.noPlaylist);
  Q('m').innerHTML='<div class="list">'+r.groups.map(g=>{const i=add({k:'g',g});return '<div class="row" data-i="'+i+'">'+(g.id==='*fav'?'<div class="star">★</div>':'')+
   '<div class="mid"><div class="t">'+esc(g.name)+'</div></div><div class="cnt">'+g.count+'</div><div class="go">›</div></div>'}).join('')+'</div>'+
   '<div class="list"><div class="row" data-i="'+add({k:'h'})+'"><div class="mid"><div class="t">'+esc(S.manage)+'</div><div class="s">'+esc(S.menuGroups)+' · '+esc(S.hiddenChannels)+'</div></div><div class="go">›</div></div></div>'}
 catch(e){fail(my)}}
let obs,editing=false;
async function group(id){const my=++seq;editing=false;Q('m').innerHTML='<div class="list" id="gl"></div><div class="more" id="more"></div>';let offs=0,total=1,busy=false;
 if(obs)obs.disconnect();
 if(id==='*fav'){Q('nava').innerHTML='<button class="pill" id="ord">'+esc(S.order)+'</button>';Q('ord').onclick=()=>{editing=!editing;buzz();Q('ord').textContent=editing?S.done:S.order;Q('ord').className='pill'+(editing?' on':'');favList()}}
 async function page(){if(busy||offs>=total||my!==seq)return;busy=true;
  try{const r=await get('group?id='+encodeURIComponent(id)+'&offset='+offs+'&limit='+(id==='*fav'?100:40));if(my!==seq)return;
   if(r.ok===false)return fail(my,r.error);
   total=r.total;offs+=r.channels.length;if(!r.channels.length)total=offs;if(id==='*fav'){favCh=r.channels;favList()}else Q('gl').insertAdjacentHTML('beforeend',r.channels.map(chRow).join(''));
   if(!total)Q('m').innerHTML='<div class="empty">'+esc(id==='*fav'?S.favEmpty:S.nothing)+'</div>'}
  catch(e){if(my===seq)toast(S.offline,true)}finally{busy=false}
  // the sentinel may still be on screen after a short page: keep going until it is not
  const m=Q('more');if(m&&my===seq&&offs<total&&m.getBoundingClientRect().top<innerHeight+600)page()}
 const g=await get('groups').catch(()=>null);if(my!==seq)return;const gg=g&&g.groups&&g.groups.find(x=>x.id===id);Q('navt').textContent=gg?gg.name:id;
 obs=new IntersectionObserver(es=>{if(es.some(e=>e.isIntersecting))page()},{rootMargin:'600px'});obs.observe(Q('more'));page()}
// the favourites, in the user's order; while «Змінити порядок» is on, each row moves up or down a step
let favCh=[];
function favList(){items.length=0;const l=Q('gl');if(!l)return;
 l.innerHTML=favCh.map((c,k)=>editing?'<div class="row">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(c.name)+'</div></div>'+
  '<button class="ib" data-mv="-1" data-id="'+esc(c.id)+'"'+(k?'':' disabled style="opacity:.25"')+'>▲</button><button class="ib" data-mv="1" data-id="'+esc(c.id)+'"'+(k<favCh.length-1?'':' disabled style="opacity:.25"')+'>▼</button>'+star(c)+'</div>':chRow(c)).join('')}
async function move(id,dir){const k=favCh.findIndex(c=>c.id===id),j=k+dir;if(k<0||j<0||j>=favCh.length)return;buzz(8);
 const c=favCh[k];favCh[k]=favCh[j];favCh[j]=c;favList();const r=await post('fav/move',{channelId:id,dir});if(!r||r.ok===false)route()}
async function schedule(chId,d){const my=++seq;Q('bk').textContent='‹';
 try{const r=await get('schedule?channel='+encodeURIComponent(chId)+'&day='+d);if(my!==seq)return;
  if(r.ok===false){Q('days').innerHTML='';return fail(my,r.error)}
  const ch=r.channel;const ci=add({k:'c',c:ch});Q('navt').innerHTML='<div style="display:flex;align-items:center;gap:10px">'+logo(ch.logo,ch.name)+'<div class="t">'+esc(ch.name)+'</div></div>';
  Q('nava').innerHTML='<div style="display:flex">'+star(ch)+'<button class="ib" id="chm" aria-label="'+esc(S.channel)+'">⋯</button></div>';Q('chm').onclick=()=>chMenu(ch,true);
  Q('days').innerHTML=r.days.map(x=>'<button data-d="'+x.day+'" class="'+(x.day===r.day?'on':'')+'">'+esc(day(x.start+12*3600e3))+'</button>').join('');
  const on=Q('days').querySelector('.on');if(on)on.scrollIntoView({inline:'center',block:'nearest'});
  Q('days').onclick=e=>{const b=e.target.closest('button');if(b)location.replace('#s/'+encodeURIComponent(chId)+'/'+b.dataset.d)};
  let h=r.programmes.length?'<div class="list">'+r.programmes.map(p=>slRow(p,ch)).join('')+'</div>':'<div class="empty">'+esc(S.noProgs)+'</div>';
  if(r.indexing)h+='<div class="note">'+esc(S.indexing)+'</div>';Q('m').innerHTML=h;
  const cur=Q('m').querySelector('.row.cur');if(cur)cur.scrollIntoView({block:'center'})}
 catch(e){fail(my)}}
// Settings → «Групи каналів» and «Приховані канали», on the phone: a switch per group, «Показати» per hidden channel
async function hidden(){const my=++seq;
 try{const r=await get('hidden');if(my!==seq)return;if(r.ok===false)return fail(my,r.error);
  let h='<h2>'+esc(S.hiddenChannels)+'</h2>'+(r.channels.length?'<div class="list">'+r.channels.map(c=>'<div class="row">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(c.name)+'</div><div class="s">'+c.num+' · '+esc(c.group)+'</div></div><button class="pill" data-un="'+esc(c.id)+'" data-n="'+esc(c.name)+'">'+esc(S.show)+'</button></div>').join('')+'</div>':'<div class="note">'+esc(S.hiddenNone)+'</div>');
  h+='<h2>'+esc(S.menuGroups)+'</h2><div class="list">'+r.groups.map(g=>'<div class="row"><div class="mid"><div class="t">'+esc(g.name)+'</div></div><div class="cnt">'+g.count+'</div><button class="sw'+(g.shown?' on':'')+'" data-g="'+esc(g.name)+'" aria-label="'+esc(g.name)+'"></button></div>').join('')+'</div>';
  Q('m').innerHTML=h}catch(e){fail(my)}}

// ---------------------------------------------------------------- sheets: a channel's menu, a programme's details
function sheet(html,acts){const c=Q('shc');c.innerHTML='<div class="grab"></div>'+html+'<div class="acts"></div>';const a=c.querySelector('.acts');
 for(const x of acts.concat([{l:S.cancel}])){const b=document.createElement('button');b.textContent=x.l;if(x.c)b.className=x.c;b.onclick=()=>{closeSheet();if(x.f)x.f()};a.appendChild(b)}
 Q('sh').className='sheet on'}
function closeSheet(){Q('sh').className='sheet'}
Q('sh').onclick=e=>{if(e.target.id==='sh')closeSheet()};
function chMenu(c,inSchedule){buzz(20);shSeq++;const on=favs.has(c.id);
 sheet('<div class="ph">'+logo(c.logo,c.name)+'<div class="mid"><div class="t">'+esc(c.name)+'</div><div class="s">'+c.num+' · '+esc(c.group)+'</div></div></div>',
  [{l:S.live,c:'pri',f:()=>play({channelId:c.id})}].concat(inSchedule?[]:[{l:S.schedule,f:()=>go('s/'+encodeURIComponent(c.id)+'/0')}],
   [{l:on?S.favRemove:S.favAdd,f:()=>toggleFav(c.id,c.name)},{l:S.hide,c:'red',f:()=>hideCh(c)}]))}
async function hideCh(c){buzz();const j=await post('hide',{channelId:c.id,hidden:true});if(j&&j.ok!==false){toast(fmt(S.hidden,c.name));if(view==='s')back('groups');else route()}}
function groupMenu(g){if(g.id==='*fav')return;buzz(20);sheet('<div class="t">'+esc(g.name)+'</div><div class="s">'+g.count+'</div>',[{l:S.hideGroup,c:'red',f:async()=>{const j=await post('group/show',{group:g.id,shown:false});if(j&&j.ok!==false)route()}}])}
// a programme: what it is, its description, and what can be done with it now
let shSeq=0;
async function progSheet(p){const k=p.when,me=++shSeq;const live=()=>me===shSeq&&Q('sh').className==='sheet on';
 const draw=(d)=>{const acts=[];const resume=d&&d.resumeAt;const inArc=d?d.inArchive:true;
  if(k==='archive'&&inArc){if(resume)acts.push({l:fmt(S.resumeAt,dur(resume-p.start)),c:'pri',f:()=>play({channelId:p.channelId,start:p.start,resume:true})});
   acts.push({l:resume?S.fromStart:S.watch,c:resume?'':'pri',f:()=>play({channelId:p.channelId,start:p.start,fromStart:true})})}
  if(k==='now'){if(resume)acts.push({l:fmt(S.resumeAt,dur(resume-p.start)),f:()=>play({channelId:p.channelId,start:p.start,resume:true})});acts.push({l:S.live,c:'pri',f:()=>play({channelId:p.channelId})});if(d?d.inArchive:true)acts.push({l:S.fromStart,f:()=>play({channelId:p.channelId,start:p.start,fromStart:true})})}
  const sub=esc(p.channel)+' · '+day(p.start)+', '+hm(p.start)+'–'+hm(p.end);
  // a guide that repeats the title as the description says nothing more
  const desc=d&&d.desc&&d.desc!==p.title?'<div class="d">'+esc(d.desc)+'</div>':(d&&!d.pending?'':'<div class="d" style="opacity:.5">…</div>');
  const why=k==='upcoming'?'<div class="note">'+esc(S.notStarted)+' · '+day(p.start)+', '+hm(p.start)+'</div>':(k==='archive'&&!inArc?'<div class="note">'+esc(S.outOfArchive)+'</div>':'');
  sheet('<div class="ph">'+logo(p.logo,p.channel)+'<div class="mid"><div class="t">'+esc(p.title)+'</div><div class="s">'+sub+'</div></div><span class="bd '+k+'">'+(k==='now'?S.now:k==='archive'?S.archive:S.upcoming)+'</span></div>'+desc+why,acts)};
 draw(null);
 // the guide reads a description on demand: ask again shortly while it is on its way
 let last={inArchive:true};
 for(let i=0;i<4;i++){const d=await get('programme?channel='+encodeURIComponent(p.channelId)+'&start='+p.start).catch(()=>null);if(!live())return;
  if(d&&d.ok!==false){last=d;draw(d);if(!d.pending)return}await new Promise(r=>setTimeout(r,500+i*400))}
 if(live())draw(Object.assign({},last,{pending:false}))}

// ---------------------------------------------------------------- taps; holding a row opens its menu
let lp=null,lpFired=false,lpXY=null;
Q('m').addEventListener('pointerdown',e=>{const r=e.target.closest('.row,.tile');if(!r||e.target.closest('button'))return;lpFired=false;lpXY=[e.clientX,e.clientY];
 lp=setTimeout(()=>{const it=items[+r.dataset.i];if(!it)return;if(it.k==='c'){lpFired=true;chMenu(it.c)}else if(it.k==='g'){lpFired=true;groupMenu(it.g)}},480)});
for(const ev of ['pointerup','pointercancel','pointerleave'])Q('m').addEventListener(ev,()=>clearTimeout(lp));
Q('m').addEventListener('pointermove',e=>{if(lpXY&&Math.hypot(e.clientX-lpXY[0],e.clientY-lpXY[1])>10)clearTimeout(lp)});
Q('m').addEventListener('contextmenu',e=>{if(e.target.closest('.row,.tile'))e.preventDefault()});
Q('m').addEventListener('click',async e=>{if(lpFired){lpFired=false;return}
 const f=e.target.closest('[data-f]');if(f)return toggleFav(f.dataset.f,f.dataset.n);
 const mv=e.target.closest('[data-mv]');if(mv)return move(mv.dataset.id,+mv.dataset.mv);
 const un=e.target.closest('[data-un]');if(un){buzz();const j=await post('hide',{channelId:un.dataset.un,hidden:false});if(j&&j.ok!==false){toast(fmt(S.back,un.dataset.n));hidden()}return}
 const sw=e.target.closest('[data-g]');if(sw){const on=!sw.classList.contains('on');sw.classList.toggle('on',on);buzz();const j=await post('group/show',{group:sw.dataset.g,shown:on});if(!j||j.ok===false)sw.classList.toggle('on',!on);return}
 const x=e.target.closest('[data-x]');if(x){const it=items[+x.dataset.x];if(!it)return;buzz();const r=x.closest('.row');r.style.display='none';
  const j=await post('bookmark/forget',{channelId:it.b.channelId,start:it.b.start});if(!j||j.ok===false)r.style.display='';else{toast(S.forgotten);if(!r.parentNode.querySelector('.row:not([style*="none"])'))home()}return}
 const sc=e.target.closest('.chev');if(sc){const it=items[+sc.dataset.s];if(it)go('s/'+encodeURIComponent(it.c.id)+'/0');return}
 const r=e.target.closest('.row,.tile');if(!r)return;const it=items[+r.dataset.i];if(!it)return;
 if(it.k==='g')return go('g/'+encodeURIComponent(it.g.id));
 if(it.k==='h')return go('hidden');
 if(it.k==='c')return play({channelId:it.c.id});
 if(it.k==='b')return play({channelId:it.b.channelId,start:it.b.start,resume:true});
 progSheet(it.p)});
Q('q').placeholder=S.search;Q('tH').textContent=S.tabHome;Q('tS').textContent=S.tabSearch;Q('tC').textContent=S.channels;

// ---------------------------------------------------------------- the player: the strip at the bottom, and the panel it opens
const key=k=>{buzz();if(k==='playpause'&&np){np.paused=!np.paused;draw()}return post('key',{key:k})};
Q('bp').onclick=e=>{e.stopPropagation();if(canPlay())key('prev')};Q('bn2').onclick=e=>{e.stopPropagation();if(canPlay())key('next')};
Q('bpp').onclick=e=>{e.stopPropagation();if(canPlay())key('playpause')};
Q('np').onclick=()=>{Q('ps').className='sheet on';drawPanel(true);now()};
Q('ps').onclick=e=>{if(e.target.id==='ps')Q('ps').className='sheet'};
const open=()=>Q('ps').className==='sheet on';
// where the TV is now: the last answer moved on by the time since, unless paused
function pos(){if(!np)return 0;return np.paused?np.position:np.position+(Date.now()-npAt)}
function range(){const p=np.programme,x=pos();if(p&&p.end>p.start)return [p.start,p.end];return [x-1800e3,x+1800e3]}
function applyNow(n){if(n.serverNow)off=n.serverNow-Date.now();np=n;npAt=Date.now();reach=true;banner();draw()}
function draw(){const f=Q('np');if(!np||!np.playing){f.style.display='none';if(open())Q('ps').className='sheet';return}f.style.display='flex';
 const c=np.channel,p=np.programme;if(lastLogo!==c.id){lastLogo=c.id;Q('npl').outerHTML=logo(c.logo,c.name,'npl')}
 Q('npt').textContent=p?p.title:c.name;Q('nps').textContent=c.name+' · '+(np.paused?S.paused:np.mode==='live'?S.live:hm(pos()));
 const [a,b]=range();Q('npg').style.width=(Math.max(0,Math.min(1,(pos()-a)/(b-a)))*100).toFixed(1)+'%';if(open())drawPanel(false)}
let dragT=null,lastLogo=null,sig='';
function drawPanel(full){if(!np||!np.playing)return;const c=np.channel,p=np.programme,x=dragT==null?pos():dragT,[a,b]=range(),t=nowT();
 const lo=Math.max(a,np.earliest||t),hi=Math.min(b,t),pct=v=>(Math.max(0,Math.min(1,(v-a)/(b-a)))*100).toFixed(2)+'%';
 const can=new Set(np.can||[]),live=np.mode==='live',behind=t-x;
 // rebuilt when what it lists changes (another channel, the audio tracks arriving), never under a finger
 const s=c.id+'|'+JSON.stringify(np.audio||[]);if(s!==sig&&dragT==null){sig=s;full=true}
 if(full||!Q('sk')){const au=(np.audio||[]);
  Q('psc').innerHTML='<div class="grab"></div><div class="bn" id="pbn" style="margin:0 6px 12px"></div><div class="ph">'+logo(c.logo,c.name,'pll')+'<div class="mid"><div class="t" id="pcn"></div><div class="s" id="pcs"></div></div>'+star(c)+'</div>'+
   '<div class="pt" id="ptt"></div><div class="pm" id="pmm"></div>'+
   '<div class="sk" id="sk"><div class="tr"></div><div class="av" id="skav"></div><div class="fl" id="skfl"></div><div class="th" id="skth"></div><div class="bb" id="skbb"></div></div>'+
   '<div class="tl"><span id="tla"></span><b id="tlx"></b><span id="tlb"></span></div>'+
   '<div class="pb"><button data-k="previous_programme" aria-label="'+esc(S.prevProg)+'">⏮</button><button data-by="-300000">−'+esc(fmt(S.min,5))+'</button><button data-by="-30000">−'+esc(fmt(S.sec,30))+'</button>'+
   '<button class="big" id="pp" data-k="playpause"></button><button data-by="30000">+'+esc(fmt(S.sec,30))+'</button><button data-by="300000">+'+esc(fmt(S.min,5))+'</button><button data-k="next_programme" aria-label="'+esc(S.nextProg)+'">⏭</button></div>'+
   '<div class="pb"><button data-k="restart">⟲ '+esc(S.fromStart)+'</button><button data-k="live" style="color:var(--live)">● '+esc(S.live)+'</button><button data-k="prev">'+esc(S.channel)+' ▼</button><button data-k="next">'+esc(S.channel)+' ▲</button></div>'+
   '<div style="margin:0 6px 14px">'+volBox()+'</div>'+
   (au.length>1?'<div class="chips"><span>'+esc(S.audio)+'</span>'+au.map(t=>'<button data-au="'+t.index+'" class="'+(t.selected?'on':'')+'">'+esc(t.name)+'</button>').join('')+'</div>':'')+
   '<div class="d" id="pds"></div>';
  seekable();paintVol()}
 Q('pbn').textContent=S.tvBg;Q('pbn').classList.toggle('on',!fg());Q('pcn').textContent=c.name;Q('pcs').textContent=c.num+' · '+c.group;Q('ptt').textContent=p?p.title:c.name;
 Q('pmm').innerHTML='<span class="bd '+(np.paused?'paused':live?'live':'archive')+'">'+esc(np.paused?S.paused:live?S.live:S.archive)+'</span>'+(p?'<span>'+day(p.start)+', '+hm(p.start)+'–'+hm(p.end)+'</span>':'')+(!live&&behind>60e3?'<span>· '+esc(fmt(S.behind,'−'+dur(behind)))+'</span>':'');
 Q('skav').style.left=pct(lo);Q('skav').style.width=(Math.max(0,(hi-lo)/(b-a))*100).toFixed(2)+'%';Q('skfl').style.width=pct(x);Q('skth').style.left=pct(x);Q('skbb').style.left=pct(x);Q('skbb').textContent=hms(x);
 Q('sk').className='sk'+(np.canSeek?'':' off')+(dragT!=null?' drag':'');
 Q('tla').textContent=hm(a);Q('tlb').textContent=hm(b);Q('tlx').textContent=hms(x);
 Q('pp').textContent=np.paused?'▶':'❚❚';
 Q('psc').querySelectorAll('[data-by]').forEach(b=>b.disabled=!np.canSeek||(+b.dataset.by>0&&live));
 Q('psc').querySelectorAll('[data-k]').forEach(b=>{const k=b.dataset.k;if(k==='restart'||k==='previous_programme'||k==='next_programme'||k==='live')b.disabled=!can.has(k)});
 const d=p&&p.desc&&p.desc!==p.title?p.desc:'';Q('pds').textContent=d||'';Q('pds').style.display=d?'':'none'}
// the seek bar: drag (or tap) to a moment; it is sent when let go, clamped to what the archive holds and to now
function seekable(){const sk=Q('sk');
 const at=e=>{const r=sk.getBoundingClientRect(),[a,b]=range(),t=nowT(),lo=Math.max(a,np.earliest||t),hi=Math.min(b,t);return Math.max(lo,Math.min(hi,a+(b-a)*(e.clientX-r.left)/r.width))};
 sk.onpointerdown=e=>{if(!np.canSeek)return;if(!canPlay())return;sk.setPointerCapture(e.pointerId);dragT=at(e);buzz(6);drawPanel(false)};
 sk.onpointermove=e=>{if(dragT==null)return;dragT=at(e);drawPanel(false)};
 sk.onpointerup=async e=>{if(dragT==null)return;const t=Math.round(at(e));dragT=null;np.position=t;npAt=Date.now();drawPanel(false);buzz();await post('seek',{to:t})};
 sk.onpointercancel=()=>{dragT=null;drawPanel(false)}}
Q('psc').addEventListener('click',async e=>{if(e.target.closest('.vol'))return;const f=e.target.closest('[data-f]');if(f)return toggleFav(f.dataset.f,f.dataset.n);
 const b=e.target.closest('button');if(!b||b.disabled)return;
 if(b.dataset.au!=null){if(!canPlay())return;buzz();const j=await post('audio',{index:+b.dataset.au});if(j&&j.tracks){np.audio=j.tracks;drawPanel(true)}return}
 if(!canPlay())return;
 if(b.dataset.by){buzz();np.position=Math.min(nowT(),pos()+(+b.dataset.by));npAt=Date.now();drawPanel(false);return post('seek',{by:+b.dataset.by})}
 if(b.dataset.k)key(b.dataset.k)});
// ---------------------------------------------------------------- the TV's volume: mute, − / + (held, they repeat), a slider over the TV's steps
const SPK='<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9h4l5-4v14l-5-4H4z" fill="currentColor"/>';
const volBox=()=>'<div class="vol off" data-vol><button data-vm></button><button data-vb="-1" aria-label="−">−</button><input type="range" min="0" max="100" value="0" data-vr aria-label="'+esc(S.volume)+'"><button data-vb="1" aria-label="+">+</button><div class="n" data-vn></div></div>';
let vol=null,vAt=0,vBusy=false,vNext=null,vHold=null;
function paintVol(){document.querySelectorAll('[data-vol]').forEach(v=>{v.classList.toggle('off',!vol);if(!vol)return;v.classList.toggle('mu',vol.muted);
 const r=v.querySelector('[data-vr]');r.max=vol.max;r.value=vol.level;r.style.setProperty('--p',(vol.max?vol.level*100/vol.max:0).toFixed(1)+'%');
 v.querySelector('[data-vn]').textContent=vol.level;const m=v.querySelector('[data-vm]');m.setAttribute('aria-label',vol.muted?S.unmute:S.mute);m.title=vol.muted?S.muted:'';
 m.innerHTML=SPK+(vol.muted?'<path d="m17 9 5 6m0-6-5 6"/></svg>':'<path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"/></svg>')})}
// what the TV says, unless the user just moved it here (an answer to an older request must not pull the slider back)
function volGet(force){if(document.hidden||vBusy||vHold||Date.now()-vAt<1500||!(force||document.querySelector('[data-vol]')))return;
 get('volume').then(v=>{if(v&&v.max!=null&&!vBusy&&!vHold&&Date.now()-vAt>=1500){vol=v;paintVol()}}).catch(()=>{})}
// one request at a time, the latest wish merged into the next one; an answer is drawn with what is still to be sent
const vClamp=l=>Math.max(0,Math.min(vol.max,l));
function vShow(j){vol=Object.assign({},j);const w=vNext;
 if(w){if(w.level!=null||w.by){vol.level=w.level!=null?w.level:vClamp(vol.level+w.by);vol.muted=vol.level===0}if(w.mute!=null)vol.muted=w.mute}paintVol()}
async function vSend(b){const w=vNext||{};
 if(b.level!=null){delete w.by;delete w.mute;w.level=b.level}
 else if(b.by){delete w.mute;if(w.level!=null)w.level=vClamp(w.level+b.by);else w.by=(w.by||0)+b.by}
 else w.mute=b.mute;
 vNext=w;if(vBusy)return;vBusy=true;
 try{while(vNext){const x=vNext;vNext=null;const j=await post('volume',x);vAt=Date.now();if(j&&j.max!=null)vShow(j)}}finally{vBusy=false}}
// as the TV does it: a level above 0 unmutes, 0 mutes; − / + of a muted TV go from the level it had (the TV knows it, the page sees 0)
function volSet(l){if(!vol)return;vol.level=vClamp(l);vol.muted=vol.level===0;vAt=Date.now();paintVol();vSend({level:vol.level})}
function volStep(d){if(!vol)return;vAt=Date.now();if(vol.muted)vol.muted=false;else{vol.level=vClamp(vol.level+d);vol.muted=vol.level===0}paintVol();vSend({by:d})}
function volStop(){if(vHold){clearTimeout(vHold.t);clearInterval(vHold.i);vHold=null}}
document.addEventListener('pointerdown',e=>{const b=e.target.closest('[data-vb]');if(!b||!vol)return;e.preventDefault();volStop();const d=+b.dataset.vb;buzz(8);volStep(d);
 vHold={t:setTimeout(()=>{vHold.i=setInterval(()=>{buzz(4);volStep(d)},110)},400)}});
for(const ev of ['pointerup','pointercancel'])document.addEventListener(ev,volStop);
window.addEventListener('blur',volStop);
document.addEventListener('contextmenu',e=>{if(e.target.closest('.vol'))e.preventDefault()});
document.addEventListener('input',e=>{if(e.target.matches('[data-vr]'))volSet(+e.target.value)});
document.addEventListener('click',e=>{const m=e.target.closest('[data-vm]');if(!m||!vol)return;buzz();vol.muted=!vol.muted;vAt=Date.now();paintVol();vSend({mute:vol.muted})});

// polling: gently while the panel is closed, often while it is open, never while the page is hidden
let pollT;
async function now(){clearTimeout(pollT);if(document.hidden)return;volGet();
 try{applyNow(await get('now'))}catch(e){reach=false;banner()}
 pollT=setTimeout(now,open()?2000:8000)}
setInterval(()=>{if(!document.hidden&&np&&np.playing&&!np.paused){if(open())drawPanel(false);else draw()}},1000);
route();now();
document.addEventListener('visibilitychange',()=>{if(!document.hidden){now();if(view==='home')home()}else clearTimeout(pollT)});
</script></body></html>
"""
}
