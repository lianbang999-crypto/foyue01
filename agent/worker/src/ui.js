// 独立站门面页 —— 极简问答界面（写经格局配色：宣纸底＋墨字＋朱砂唯一点睛）
//
// 单文件字符串随 worker 打包，无静态资源依赖。SSE 解析与主站 app.js 同式。
// 出处卡链接直指主站原文静态资源（/text/<path>），逐条可溯源。

export const PAGE = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>问道 · 佛乐文库智能体</title>
<meta name="description" content="基于大安法师讲记与答问全库作答，逐句有据可查。">
<style>
  :root { --paper:#f3ecda; --ink:#2c261c; --ink2:#6b6252; --zhu:#bd3a26; --gold:#b8965a; }
  * { box-sizing:border-box; margin:0; }
  body { background:var(--paper); color:var(--ink); font:16px/1.8 "Songti SC","Noto Serif SC",serif;
         max-width:720px; margin:0 auto; padding:28px 18px 60px; }
  header { border-bottom:1px solid color-mix(in srgb,var(--gold) 45%,transparent); padding-bottom:14px; margin-bottom:18px; }
  h1 { font-size:1.35rem; letter-spacing:.12em; }
  h1 b { color:var(--zhu); font-weight:600; }
  .sub { color:var(--ink2); font-size:.85rem; margin-top:6px; }
  form { display:flex; gap:10px; margin:16px 0; }
  textarea { flex:1; resize:none; height:64px; padding:10px 12px; font:inherit; font-size:.95rem;
             background:#fffdf6; border:1px solid color-mix(in srgb,var(--gold) 55%,transparent); border-radius:8px; }
  button { padding:0 22px; font:inherit; letter-spacing:.2em; color:#fff; background:var(--zhu);
           border:0; border-radius:8px; cursor:pointer; }
  button:disabled { opacity:.5; cursor:default; }
  .mode { font-size:.78rem; color:var(--ink2); margin:6px 0; }
  .mode em { font-style:normal; color:var(--zhu); }
  #out { white-space:pre-wrap; background:#fffdf6; border:1px solid color-mix(in srgb,var(--gold) 40%,transparent);
         border-radius:10px; padding:16px 18px; min-height:90px; font-size:.95rem; }
  #out:empty::before { content:"所答皆出自大安法师讲记与答问全库，逐条可溯源。命中亲答者原文照录，综述句句缀出处角标。"; color:var(--ink2); font-size:.85rem; }
  sup { color:var(--zhu); font-size:.7em; }
  #src { margin-top:14px; }
  .sc { display:block; text-decoration:none; color:inherit; border-left:3px solid var(--gold);
        background:#fffdf6; padding:8px 12px; margin:8px 0; border-radius:0 8px 8px 0; }
  .sc b { font-size:.85rem; } .sc b i { color:var(--zhu); font-style:normal; margin-right:6px; }
  .sc p { color:var(--ink2); font-size:.78rem; margin-top:2px; }
  footer { margin-top:34px; color:var(--ink2); font-size:.78rem; text-align:center; }
  footer a { color:var(--zhu); text-decoration:none; }
</style>
</head>
<body>
<header>
  <h1>问道 · <b>佛乐文库智能体</b></h1>
  <div class="sub">依大安法师讲记与答问全库作答 · 有据可查 · 不代法师立言</div>
</header>
<form id="f">
  <textarea id="q" maxlength="300" placeholder="请写下您的修学之问……"></textarea>
  <button id="go">问</button>
</form>
<div class="mode" id="mode"></div>
<div id="out"></div>
<div id="src"></div>
<footer>返回 <a href="https://foyue.org">佛乐 foyue.org</a> · 答语由 AI 检索转述，义理以原文为准</footer>
<script>
const $ = (id) => document.getElementById(id);
const esc = (s) => s.replace(/[&<>]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
let busy = false;
$('f').addEventListener('submit', async (e) => {
  e.preventDefault();
  const q = $('q').value.trim();
  if (!q || busy) return;
  busy = true; $('go').disabled = true;
  $('out').textContent = ''; $('src').innerHTML = ''; $('mode').textContent = '检索中……';
  let text = '', sources = [];
  const render = () => { $('out').innerHTML = esc(text).replace(/\\[(\\d{1,2})\\]/g, '<sup>[$1]</sup>'); };
  try {
    const res = await fetch('/v1/ask', { method:'POST', headers:{'content-type':'application/json'},
      body: JSON.stringify({ q }) });
    if (!res.ok) throw new Error(await res.text() || res.status);
    const rd = res.body.getReader(); const dec = new TextDecoder(); let buf = '';
    while (true) {
      const { done, value } = await rd.read();
      if (done) break;
      buf += dec.decode(value, { stream:true });
      const frames = buf.split('\\n\\n'); buf = frames.pop();
      for (const fr of frames) {
        const ev = fr.match(/^event: (\\w+)/m)?.[1];
        const dl = fr.match(/^data: (.*)$/m)?.[1];
        if (!ev || !dl) continue;
        const data = JSON.parse(dl);
        if (ev === 'mode') $('mode').innerHTML = '<em>' + esc(data.basis?.label || data.mode) + '</em>';
        else if (ev === 'sources') sources = data;
        else if (ev === 'delta') { text += data.text; render(); }
        else if (ev === 'done') {
          if (data.evidenceStatus === 'ungrounded') $('mode').innerHTML += ' · <em>部分句子未过引文核验，请以原文为准</em>';
          if (typeof data.remaining === 'number') $('mode').innerHTML += ' · 今日余 ' + data.remaining;
        }
      }
    }
    $('src').innerHTML = sources.map((s) =>
      '<a class="sc" target="_blank" href="https://foyue.org/text/' + esc(s.path) + '">' +
      '<b><i>[' + s.n + ']</i>' + esc(s.series || '') + ' · ' + esc(s.title || '') + '</b>' +
      '<p>' + esc(s.x || '') + '…</p></a>').join('');
  } catch (err) {
    $('mode').textContent = '';
    $('out').textContent = String(err.message || '网络异常，请稍后再试').slice(0, 120);
  }
  busy = false; $('go').disabled = false;
});
</script>
</body>
</html>`;
