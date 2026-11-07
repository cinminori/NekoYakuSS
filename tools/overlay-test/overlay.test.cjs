/*
 * assets/overlay.js 的瀏覽器測試：用 Playwright 的 Chromium 開模擬頁面（不連真實網站），
 * 以假的 window.NekoYaku 代替 App，驗證段落偵測、疊翻、模式切換、重繪補回、插圖、跑馬燈、
 * 字色、翻頁模式自動切捲動、網站介面翻譯。
 *
 * 執行：cd tools/overlay-test && npm install && npx playwright install chromium && npm test
 * 全部通過回傳 0；任何一項失敗回傳 1。
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const SCRIPT = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/overlay.js'), 'utf8');
const KAKAO_RE = '^/content/(\\d+)/viewer/(\\d+)/?$';
const RIDI_RE = '^/books/(\\d+)/view/?$';
const KO = (i) => '그는 문을 열었다 ' + i + '번째 문장이다. 이 문장은 꽤 길게 써서 화면을 채운다.';

let failures = 0;
function check(name, cond, detail) {
  if (cond) {
    console.log('  ok   ' + name);
  } else {
    failures++;
    console.log('  FAIL ' + name + (detail !== undefined ? '  ' + JSON.stringify(detail) : ''));
  }
}

/** 開一個模擬頁：body＝頁面 HTML，bridge＝在頁面裡執行的假 App（字串形式的函式）。 */
async function openPage(browser, url, body, bridge, viewport) {
  const page = await browser.newPage({ viewport: viewport || { width: 400, height: 800 } });
  await page.route('**/*', (r) => r.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body }));
  await page.addInitScript(bridge);
  await page.goto(url);
  return page;
}
async function inject(page, cfg) {
  return page.evaluate('window.__nyCfg=' + JSON.stringify(cfg) + ';\n' + SCRIPT + '\n;window.__ny?window.__ny.sid():""');
}

/** 預設假 App：段落請求 30ms 後回「譯：原文」。 */
const ECHO_BRIDGE = () => {
  window.__reqs = [];
  window.__pages = [];
  window.__notes = [];
  window.NekoYaku = {
    request: (s) => {
      const j = JSON.parse(s);
      window.__reqs.push(j);
      setTimeout(() => window.__ny.apply(j.sid, j.items.map((it) => ({ t: it.t, tr: '譯：' + it.t }))), 30);
    },
    page: (s) => window.__pages.push(JSON.parse(s)),
    trMenu: (s) => (window.__menus = window.__menus || []).push(JSON.parse(s)),
    note: (s) => window.__notes.push(JSON.parse(s).kind),
    image: (s) => {
      const j = JSON.parse(s);
      setTimeout(() => window.__ny.applyImg(j.sid, j.i, j.src, '圖中譯文'), 30);
    },
    ui: (s) => {
      const j = JSON.parse(s);
      (window.__ui = window.__ui || []).push(...j.items.map((i) => i.t));
      setTimeout(() => window.__ny.uiApply(j.sid, j.items.map((it) => ({ t: it.t, tr: '〈' + it.t + '〉' }))), 30);
    },
  };
};

async function testKakaoShadow(browser) {
  console.log('kakaopage：shadow DOM 正文、模式切換、重繪補回');
  const body = `<!doctype html><html><head><meta property="og:title" content="테스트 작품 3화"></head><body>
    <div id="app"><p>로그인</p><div id="host"></div></div><script>
    setTimeout(() => { const s = document.getElementById('host').attachShadow({ mode: 'open' });
      let h = '<div>'; for (let i = 0; i < 60; i++) h += '<p style="font-size:18px">' + ${JSON.stringify(KO('X'))}.replace('X', i) + '</p>';
      h += '<div class="box1"><div class="chat">감독님: 코치 자리 있다.</div></div></div>'; s.innerHTML = h; }, 300);
    </script></body></html>`;
  const page = await openPage(browser, 'https://page.kakao.com/content/1/viewer/2/', body, ECHO_BRIDGE);
  const sid = await inject(page, { roots: [], shadow: true, paras: 'p,div,li,td,th,h1,h2,h3,h4,blockquote', minParas: 5, pathRe: KAKAO_RE, auto: true });
  check('注入回傳 sid', typeof sid === 'string' && sid.length > 0, sid);
  await page.waitForTimeout(1500);
  const r1 = await page.evaluate(() => ({ stats: JSON.parse(__ny.stats()), pages: __pages.length, firstCtx: (__reqs[0] || {}).ctx }));
  check('找到 61 段（含框內 div）', r1.stats.total === 61, r1.stats);
  check('畫面附近的段落已翻', r1.stats.done > 0 && r1.stats.fail === 0, r1.stats);
  check('回報一次章節頁', r1.pages === 1, r1.pages);
  const light = await page.evaluate(() => !!document.querySelector('#app > p ny-tr'));
  check('shadow root 外的 light DOM 不翻', !light);
  await page.evaluate(() => __ny.setMode('tr'));
  const tr = await page.evaluate(() => {
    const t = document.getElementById('host').shadowRoot.querySelector('ny-tr');
    return { src: getComputedStyle(t.parentElement).fontSize, tr: getComputedStyle(t).fontSize };
  });
  check('只看譯文：原文字級 0、譯文照原字級', tr.src === '0px' && tr.tr === '18px', tr);
  await page.evaluate(() => __ny.setMode('src'));
  const disp = await page.evaluate(() => getComputedStyle(document.getElementById('host').shadowRoot.querySelector('ny-tr')).display);
  check('只看原文：譯文隱藏', disp === 'none', disp);
  await page.evaluate(() => __ny.setMode('dual'));
  const before = await page.evaluate(() => __reqs.length);
  await page.evaluate(() => {
    const p = document.getElementById('host').shadowRoot.querySelector('p');
    p.textContent = p.textContent.replace(/譯：.*/, '');
  });
  await page.waitForTimeout(1200);
  const re = await page.evaluate(() => ({ has: !!document.getElementById('host').shadowRoot.querySelector('p ny-tr'), reqs: __reqs.length }));
  check('網頁重繪後從記憶體補回譯文', re.has, re);
  check('重繪補回不重送請求', re.reqs >= before, { before, after: re.reqs });
  const collected = JSON.parse(await page.evaluate(() => __ny.collect()));
  check('collect 回傳全部段落原文', collected.length === 61, collected.length);
  await page.close();
}

async function testFontFollow(browser) {
  console.log('網頁閱讀器調整字級：譯文跟著變');
  const body = `<!doctype html><html><head><style>
    .fs-m p { font-size: 18px; } .fs-l p { font-size: 24px; } .fs-s p { font-size: 14px; }
    </style></head><body><div id="viewer" class="fs-m">` +
    Array.from({ length: 12 }, (_, i) => `<p id="text_${i}">${KO(i)}</p>`).join('') + `</div></body></html>`;
  const page = await openPage(browser, 'https://www.bomtoon.com/viewer/book/1', body, ECHO_BRIDGE);
  await inject(page, { roots: ['#viewer'], paras: 'p[id^="text_"]', minParas: 3, pathRe: '^/viewer/([^/]+)/([^/]+)/?$', auto: true });
  await page.waitForTimeout(1200);
  const size = () => page.evaluate(() => ({
    tr: getComputedStyle(document.querySelector('#text_0 ny-tr')).fontSize,
    src: getComputedStyle(document.getElementById('text_0')).fontSize,
    mark: document.getElementById('text_0').getAttribute('data-ny-src'),
    reqs: __reqs.length,
  }));
  const s0 = await size();
  check('一開始譯文跟原文同字級', s0.tr === '18px', s0);
  await page.evaluate(() => { document.getElementById('viewer').className = 'fs-l'; });
  await page.waitForTimeout(1000);
  const s1 = await size();
  check('雙語：外層 class 改字級，譯文跟著變大', s1.tr === '24px', s1);
  check('字級變動不重送翻譯', s1.reqs === s0.reqs, s1);
  await page.evaluate(() => { __ny.setMode('tr'); document.getElementById('viewer').className = 'fs-s'; });
  await page.waitForTimeout(1000);
  const s2 = await size();
  check('只看譯文：改字級，譯文跟著變小', s2.tr === '14px', s2);
  check('只看譯文：原文仍隱藏', s2.src === '0px' && s2.mark === '1', s2);
  await page.evaluate(() => {
    __ny.setMode('dual');
    document.querySelectorAll('#viewer p').forEach((p) => { p.style.fontSize = '30px'; });
  });
  await page.waitForTimeout(1000);
  const s3 = await size();
  check('段落 inline style 改字級也跟得上', s3.tr === '30px', s3);
  await page.close();
}

async function testHugeChapter(browser) {
  console.log('超長章節（電子書型）：翻譯整頁不超過 App 單次上限');
  const body = `<!doctype html><html><body><div id="viewer">` +
    Array.from({ length: 1000 }, (_, i) => `<p id="text_${i}">${KO(i)}</p>`).join('') + `</div></body></html>`;
  const page = await openPage(browser, 'https://www.bomtoon.com/viewer/book/2', body, ECHO_BRIDGE);
  await page.evaluate(() => {
    // 跟 App 一樣：單次超過 400 段就丟棄
    const orig = window.NekoYaku.request;
    window.__dropped = 0;
    window.NekoYaku.request = (s) => { if (JSON.parse(s).items.length > 400) { window.__dropped++; return; } orig(s); };
  });
  await inject(page, { roots: ['#viewer'], paras: 'p[id^="text_"]', minParas: 3, pathRe: '^/viewer/([^/]+)/([^/]+)/?$', auto: false });
  await page.waitForTimeout(800);
  await page.evaluate(() => __ny.translateAll());
  await page.waitForTimeout(1200);
  const r = await page.evaluate(() => ({ stats: JSON.parse(__ny.stats()), dropped: __dropped, reqs: __reqs.length, max: Math.max(0, ...__reqs.map((q) => q.items.length)), ctx: (__reqs[1] || {}).ctx }));
  check('沒有請求被 App 丟棄', r.dropped === 0, r);
  check('全部段落都翻完', r.stats.done === 1000 && r.stats.pending === 0, r.stats);
  check('分批帶前文脈絡', r.reqs > 1 && r.ctx && r.ctx.length === 2, r);
  await page.close();
}

async function testResetAutoOff(browser) {
  console.log('自動翻譯關閉時按「重新翻譯本話」：翻過的段落直接重送');
  const body = `<!doctype html><html><body><div id="viewer">` +
    Array.from({ length: 20 }, (_, i) => `<p id="text_${i}">${KO(i)}</p>`).join('') + `</div></body></html>`;
  const page = await openPage(browser, 'https://www.bomtoon.com/viewer/book/3', body, ECHO_BRIDGE);
  await inject(page, { roots: ['#viewer'], paras: 'p[id^="text_"]', minParas: 3, pathRe: '^/viewer/([^/]+)/([^/]+)/?$', auto: false });
  await page.waitForTimeout(800);
  await page.evaluate(() => __ny.translateAll());
  await page.waitForTimeout(600);
  const before = await page.evaluate(() => __reqs.reduce((n, q) => n + q.items.length, 0));
  await page.evaluate(() => __ny.reset());
  await page.waitForTimeout(600);
  const r = await page.evaluate(() => ({ stats: JSON.parse(__ny.stats()), sent: __reqs.reduce((n, q) => n + q.items.length, 0) }));
  check('重送了原本翻過的段落', r.sent - before === 20, { before, ...r });
  check('重新翻完', r.stats.done === 20 && r.stats.pending === 0, r.stats);
  await page.close();
}

async function testVolumePaging(browser) {
  console.log('音量鍵翻頁：捲到真正會動的容器');
  const paras = Array.from({ length: 60 }, (_, i) => `<p id="text_${i}" style="font-size:18px">${KO(i)}</p>`).join('');
  const cfg = { roots: ['#viewer'], paras: 'p[id^="text_"]', minParas: 3, pathRe: '^/viewer/([^/]+)/([^/]+)/?$', auto: false };
  const run = async (name, head, read) => {
    const page = await openPage(browser, 'https://www.bomtoon.com/viewer/book/3', `<!doctype html><html><head><style>${head}</style></head><body><div id="viewer">${paras}</div></body></html>`, ECHO_BRIDGE);
    await inject(page, cfg);
    await page.waitForTimeout(500);
    await page.evaluate(() => __ny.page(1));
    await page.waitForTimeout(100);
    const down = await page.evaluate(read);
    await page.evaluate(() => __ny.page(-1));
    await page.waitForTimeout(100);
    const up = await page.evaluate(read);
    check(name + '：下一頁往下捲', down > 300, down);
    check(name + '：上一頁捲回去', up < down, { down, up });
    await page.close();
  };
  await run('body 有 overflow:auto、實際捲視窗（kakaopage）', 'html,body{height:100%}body{overflow-y:auto}', () => window.scrollY);
  await run('html 也有 overflow、body 才是捲動容器', 'html,body{height:100%;overflow:hidden}body{overflow-y:auto}', () => document.body.scrollTop);
  await run('內層容器捲動（bomtoon 電子書型）', 'html,body{height:100%;margin:0;overflow:hidden}#viewer{height:100%;overflow-y:auto}', () => document.getElementById('viewer').scrollTop);
}

async function testOccurrenceOverride(browser) {
  console.log('長按譯文：只改這一處');
  const same = '네.';
  const body = `<!doctype html><html><body><div id="viewer">` +
    Array.from({ length: 12 }, (_, i) => `<p id="text_${i}">${i % 4 === 1 ? same : KO(i)}</p>`).join('') + `</div></body></html>`;
  const page = await openPage(browser, 'https://www.bomtoon.com/viewer/book/4', body, ECHO_BRIDGE);
  await inject(page, { roots: ['#viewer'], paras: 'p[id^="text_"]', minParas: 3, pathRe: '^/viewer/([^/]+)/([^/]+)/?$', auto: true });
  await page.waitForTimeout(800);
  const texts = () => page.evaluate(() => [1, 5, 9].map((i) => document.querySelector('#text_' + i + ' ny-tr').textContent));
  const press = async (id, ms) => {
    await page.evaluate((i) => document.querySelector('#text_' + i + ' ny-tr').dispatchEvent(new Event('touchstart', { bubbles: true })), id);
    await page.waitForTimeout(ms);
    await page.evaluate((i) => document.querySelector('#text_' + i + ' ny-tr').dispatchEvent(new Event('touchend', { bubbles: true })), id);
  };
  await press(5, 200);
  check('短按不開選單', (await page.evaluate(() => (window.__menus || []).length)) === 0);
  await press(5, 700);
  const m = await page.evaluate(() => (window.__menus || [])[0]);
  check('長按回報原文、第幾次出現、目前譯文', m && m.t === same && m.occ === 1 && m.tr === '譯：' + same, m);
  check('長按帶前文脈絡', m && m.ctx.length === 2, m);
  await page.evaluate((sid) => __ny.applyOv(sid, [{ t: '네.', occ: 1, tr: '喔。' }]), m.sid);
  const a = await texts();
  check('只改長按的那一處', a[0] === '譯：네.' && a[1] === '喔。' && a[2] === '譯：네.', a);
  await page.evaluate((i) => { const p = document.getElementById('text_1'); p.parentNode.replaceChild(p.cloneNode(true), p); });
  await page.waitForTimeout(1300);
  const b = await page.evaluate(() => [1, 5, 9].map((i) => (document.querySelector('#text_' + i + ' ny-tr') || {}).textContent));
  check('網頁重繪其他段後仍對應同一處', b[1] === '喔。' && b[2] === '譯：네.', b);
  await page.evaluate((sid) => __ny.applyOv(sid, [{ t: '네.', occ: 1, tr: null }]), m.sid);
  const c = await texts();
  check('恢復共用譯文', c[1] === '譯：네.', c);
  await page.close();
}

async function testTranslationFont(browser) {
  console.log('譯文字型：蓋過網站規則、shadow DOM 的網站也宣告自訂字型');
  const paras = Array.from({ length: 8 }, (_, i) => `<p id="text_${i}">${KO(i)}</p>`).join('');
  const cfg = { roots: ['#viewer'], paras: 'p[id^="text_"]', minParas: 3, pathRe: '^/viewer/([^/]+)/([^/]+)/?$', auto: true, font: 'serif' };
  // mrblue：高權重的 !important 規則
  const page = await openPage(browser, 'https://www.mrblue.com/viewer/book/1', `<!doctype html><html><head><style>div[data-id="x"] *{font-family:monospace!important}</style></head><body><div id="viewer" data-id="x">${paras}</div></body></html>`, ECHO_BRIDGE);
  await inject(page, cfg);
  await page.waitForTimeout(800);
  const fam = await page.evaluate(() => getComputedStyle(document.querySelector('#text_0 ny-tr')).fontFamily);
  check('網站高權重規則蓋不過譯文字型', fam === 'serif', fam);
  await page.close();
  // 跟隨網頁：不強制
  const p2 = await openPage(browser, 'https://www.mrblue.com/viewer/book/1', `<!doctype html><html><head><style>div[data-id="x"] *{font-family:monospace!important}</style></head><body><div id="viewer" data-id="x">${paras}</div></body></html>`, ECHO_BRIDGE);
  await inject(p2, { ...cfg, font: 'inherit' });
  await p2.waitForTimeout(800);
  const fam2 = await p2.evaluate(() => getComputedStyle(document.querySelector('#text_0 ny-tr')).fontFamily);
  check('跟隨網頁時用網站字型', fam2 === 'monospace', fam2);
  await p2.close();
  // kakaopage：正文在 shadow DOM，@font-face 只有放在主文件才有效
  const body = `<!doctype html><html><body><div id="host"></div><script>
    document.getElementById('host').attachShadow({ mode: 'open' }).innerHTML = '<div>' + Array.from({ length: 8 }, (_, i) => '<p>' + ${JSON.stringify(KO('X'))}.replace('X', i) + '</p>').join('') + '</div>';
    </script></body></html>`;
  const p3 = await openPage(browser, 'https://page.kakao.com/content/1/viewer/2/', body, ECHO_BRIDGE);
  await inject(p3, { roots: [], shadow: true, paras: 'p', minParas: 5, pathRe: KAKAO_RE, auto: true, font: 'custom' });
  await p3.waitForTimeout(800);
  const face = await p3.evaluate(() => [...document.querySelectorAll('[data-ny-style]')].some((s) => s.textContent.includes('@font-face')));
  check('shadow DOM 的網站在主文件宣告自訂字型', face);
  await p3.close();
}

async function testRidi(browser) {
  console.log('ridi：#viewer_contents、聊天泡泡、表格');
  const paras = Array.from({ length: 40 }, (_, i) => '<p>' + KO(i) + '</p>').join('');
  const body = `<!doctype html><html><body><header><p>메뉴 항목입니다</p></header>
    <div id="viewer_contents"><div class="wrap">${paras}<div class="insta_bubble">채팅 말풍선 내용이에요</div>
    <table><tr><td>이름</td><td>나이</td></tr></table></div></div></body></html>`;
  const page = await openPage(browser, 'https://ridibooks.com/books/123/view', body, ECHO_BRIDGE);
  await inject(page, { roots: ['#viewer_contents'], shadow: false, paras: 'p,div,li,td,th,h1,h2,h3,h4,h5,h6,blockquote', minParas: 3, pathRe: RIDI_RE, auto: true });
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(1500);
  const r = await page.evaluate(() => ({
    stats: JSON.parse(__ny.stats()),
    header: !!document.querySelector('header ny-tr'),
    bubble: !!document.querySelector('.insta_bubble ny-tr'),
    td: document.querySelectorAll('td ny-tr').length,
  }));
  check('找到 43 段（40 段＋泡泡＋2 格）', r.stats.total === 43, r.stats);
  check('容器外的頁首不翻', !r.header);
  check('聊天泡泡（div）有翻', r.bubble);
  check('表格每格各自翻', r.td === 2, r.td);
  await page.close();
}

async function testImagesAndColor(browser) {
  console.log('插圖、小圖示、黑底框字色');
  const body = `<!doctype html><html><body style="background:#fff;color:#222"><div id="host"></div><script>
    setTimeout(() => { const s = document.getElementById('host').attachShadow({ mode: 'open' });
      const c = document.createElement('canvas'); c.width = 600; c.height = 300;
      const g = c.getContext('2d'); g.fillStyle = '#eee'; g.fillRect(0, 0, 600, 300);
      let h = '<div>'; for (let i = 0; i < 6; i++) h += '<p>그는 문을 열었다 ' + i + '</p>';
      h += '<div style="background:#000"><p><span style="color:#0f0">목포행</span></p></div>';
      h += '<p><img src="' + c.toDataURL() + '"></p><img width="17" height="19" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">';
      h += '</div>'; s.innerHTML = h; }, 200);
    </script></body></html>`;
  for (const eink of [false, true]) {
    const page = await openPage(browser, 'https://page.kakao.com/content/1/viewer/2/', body, ECHO_BRIDGE);
    await inject(page, { roots: [], shadow: true, paras: 'p,div', minParas: 5, pathRe: KAKAO_RE, auto: true, images: true, eink });
    await page.waitForTimeout(1800);
    const r = await page.evaluate(() => {
      const root = document.getElementById('host').shadowRoot;
      const trs = [...root.querySelectorAll('ny-tr')];
      const board = trs.find((n) => n.parentElement.textContent.indexOf('목포행') === 0);
      const imgTr = trs.filter((n) => n.previousElementSibling && n.previousElementSibling.tagName === 'IMG');
      return { color: board && getComputedStyle(board).color, imgs: imgTr.length, imgText: imgTr[0] && imgTr[0].textContent };
    });
    if (!eink) {
      check('大圖下方有圖中文字、小圖示略過', r.imgs === 1 && r.imgText.indexOf('圖中譯文') >= 0, r);
      check('一般螢幕：黑底框譯文改用原文綠字', r.color === 'rgb(0, 255, 0)', r.color);
    } else {
      check('電子紙：黑底框譯文改用白字', r.color === 'rgb(255, 255, 255)', r.color);
    }
    await page.close();
  }
}

async function testMarquee(browser) {
  console.log('跑馬燈：只翻單位句、譯文跟著捲');
  const unit = '탐라에 도착하고 싶은 자여 제물을 공양해라 ';
  const body = `<!doctype html><html><body style="background:#fff"><div id="host"></div><script>
    setTimeout(() => { const s = document.getElementById('host').attachShadow({ mode: 'open' });
      let h = '<style>@keyframes m{from{transform:translateX(0)}to{transform:translateX(-100%)}}' +
        '.box1bani{background:#000;overflow:hidden}.box1bani p{white-space:nowrap;animation:m 20s linear infinite}</style><div>';
      for (let i = 0; i < 6; i++) h += '<p>그는 문을 열었다 ' + i + '</p>';
      h += '<div class="box1bani"><p><span style="color:#fc0">' + ${JSON.stringify(unit)}.repeat(115) + '</span></p></div></div>';
      s.innerHTML = h; }, 200);
    </script></body></html>`;
  for (const eink of [false, true]) {
    const page = await openPage(browser, 'https://page.kakao.com/content/1/viewer/2/', body, ECHO_BRIDGE);
    await inject(page, { roots: [], shadow: true, paras: 'p,div', minParas: 5, pathRe: KAKAO_RE, auto: true, eink });
    await page.waitForTimeout(1800);
    const r = await page.evaluate(() => {
      const root = document.getElementById('host').shadowRoot;
      const sent = [].concat(...__reqs.map((q) => q.items.map((i) => i.t.length)));
      const tr = [...root.querySelectorAll('ny-tr')].find((n) => n.textContent.indexOf('탐라') >= 0);
      return {
        maxSent: Math.max(...sent),
        cls: tr && tr.className,
        anim: tr && tr.firstChild && tr.firstChild.getAnimations ? tr.firstChild.getAnimations().length : 0,
        boxH: Math.round(root.querySelector('.box1bani').getBoundingClientRect().height),
        text: tr && tr.textContent.slice(0, 20),
      };
    });
    if (!eink) {
      check('只送單位句（不送 115 次的全文）', r.maxSent < 40, r.maxSent);
      check('一般螢幕：譯文單行捲動', r.cls === 'ny-mq' && r.anim === 1, r);
      check('框不會被撐成一大片', r.boxH < 150, r.boxH);
    } else {
      check('電子紙：靜態顯示並標示重複次數', r.cls === '' && r.text.indexOf('〔跑馬燈〕') === 0, r);
    }
    await page.close();
  }
}

async function testPagedForceScroll(browser) {
  console.log('翻頁模式：自動改成捲動並重新載入（只試一次）');
  const body = `<!doctype html><html><body style="margin:0"><div id="host"></div><script>
    setTimeout(() => { const s = document.getElementById('host').attachShadow({ mode: 'open' });
      let h = '<div style="overflow:hidden;width:400px"><div style="height:600px;width:400px;column-width:400px;column-gap:0">';
      for (let i = 0; i < 80; i++) h += '<p>그는 문을 열었다 ' + i + '번째 문장입니다. 조금 길게 써서 화면을 채운다.</p>';
      s.innerHTML = h + '</div></div>'; }, 200);
    </script></body></html>`;
  const page = await openPage(browser, 'https://page.kakao.com/content/1/viewer/2/', body, ECHO_BRIDGE);
  await inject(page, { roots: [], shadow: true, paras: 'p,div', minParas: 5, pathRe: KAKAO_RE, auto: true,
    scrollFix: { key: 'text_viewer_viewer_setting_read_type', value: 'scroll' } });
  await page.waitForTimeout(2500);
  const r = await page.evaluate(() => ({ ls: localStorage.getItem('text_viewer_viewer_setting_read_type'), tried: sessionStorage.getItem('__nyScrollFix') }));
  check('改寫閱讀方式為 scroll 並記下已試過', r.ls === 'scroll' && r.tried === '1', r);
  await page.close();
}

async function testUiTranslation(browser) {
  console.log('網站介面翻譯：換字、不碰正文、關掉還原');
  const body = `<!doctype html><html><body><nav><a href="#">홈</a> <button>다음화 무료로 보기</button></nav><div id="host"></div><script>
    setTimeout(() => { const s = document.getElementById('host').attachShadow({ mode: 'open' });
      let h = ''; for (let i = 0; i < 10; i++) h += '<p>그는 문을 열었다 ' + i + '</p>'; s.innerHTML = h; }, 200);
    </script></body></html>`;
  const page = await openPage(browser, 'https://page.kakao.com/content/1/viewer/2/', body, ECHO_BRIDGE);
  await inject(page, { roots: [], shadow: true, paras: 'p', minParas: 5, pathRe: KAKAO_RE, auto: true, ui: true });
  await page.waitForTimeout(2500);
  const r = await page.evaluate(() => ({ nav: document.querySelector('nav').textContent, ui: window.__ui || [] }));
  check('介面字串換成譯文', r.nav.indexOf('〈홈〉') >= 0, r.nav);
  check('正文段落不送介面翻譯', r.ui.every((t) => t.indexOf('그는') < 0), r.ui);
  await page.evaluate(() => __ny.setUi(false));
  const restored = await page.evaluate(() => document.querySelector('nav').textContent);
  check('關掉介面翻譯會還原原文', restored.indexOf('〈') < 0, restored);
  await page.close();
}

async function testAutoRetry(browser) {
  console.log('網路暫時斷線：20 秒後自動重送');
  const bridge = () => {
    window.__n = 0;
    window.NekoYaku = {
      request: (s) => {
        const j = JSON.parse(s);
        window.__n++;
        if (window.__n === 1) setTimeout(() => window.__ny.fail(j.sid, j.items.map((it) => it.i), '網路不穩', true), 30);
        else setTimeout(() => window.__ny.apply(j.sid, j.items.map((it) => ({ t: it.t, tr: '譯' }))), 30);
      },
      page: () => {}, note: () => {},
    };
  };
  const body = `<!doctype html><html><body><div id="viewer_contents">${Array.from({ length: 5 }, (_, i) => '<p>그는 문을 열었다 ' + i + '</p>').join('')}</div></body></html>`;
  const page = await openPage(browser, 'https://ridibooks.com/books/123/view', body, bridge);
  await inject(page, { roots: ['#viewer_contents'], paras: 'p', minParas: 3, pathRe: RIDI_RE, auto: true });
  await page.waitForTimeout(1500);
  const s1 = await page.evaluate(() => JSON.parse(__ny.stats()));
  check('第一次失敗', s1.fail === 5, s1);
  await page.waitForTimeout(22000);
  const s2 = await page.evaluate(() => JSON.parse(__ny.stats()));
  check('20 秒後自動重送並成功', s2.done === 5 && s2.fail === 0, s2);
  await page.close();
}

(async () => {
  const browser = await chromium.launch();
  try {
    await testKakaoShadow(browser);
    await testRidi(browser);
    await testFontFollow(browser);
    await testHugeChapter(browser);
    await testResetAutoOff(browser);
    await testVolumePaging(browser);
    await testOccurrenceOverride(browser);
    await testTranslationFont(browser);
    await testImagesAndColor(browser);
    await testMarquee(browser);
    await testPagedForceScroll(browser);
    await testUiTranslation(browser);
    await testAutoRetry(browser);
  } finally {
    await browser.close();
  }
  console.log(failures ? '\n' + failures + ' 項失敗' : '\n全部通過');
  process.exit(failures ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
