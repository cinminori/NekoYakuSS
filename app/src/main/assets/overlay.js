/*
 * NekoYakuSS 疊翻注入腳本。
 *
 * 在原網頁裡找出正文段落，把譯文插在每段原文的「段落內最後面」（<ny-tr> 自訂元素）。
 * - 正文位置依站別設定 window.__nyCfg（見 OverlaySite.overlayConfigJson）。
 * - 會穿透 open shadow root（kakaopage）與同源 iframe（bomtoon 電子書型）。
 * - 捲動到畫面附近的段落才送翻（IntersectionObserver），分批交給 App（window.NekoYaku）。
 * - 網頁重新 render（React 換掉段落）時，已翻過的段落直接用記憶體裡的譯文補回，不再問 App。
 *
 * 這支腳本可以重複注入：第二次只會更新設定並重新掃描。
 */
(function () {
  'use strict';
  var cfgIn = window.__nyCfg || {};
  if (window.__ny) { window.__ny.configure(cfgIn); return 'again'; }
  var B = window.NekoYaku;
  if (!B) return 'nobridge';

  // 用字碼組 regex：原始碼保持純 ASCII，不怕編碼轉換
  function cc(a) { return String.fromCharCode(a); }
  var HANGUL = new RegExp('[' + cc(0xAC00) + '-' + cc(0xD7A3) + cc(0x3131) + '-' + cc(0x318E) + ']');
  var INVIS = new RegExp('[' + cc(0x200B) + '-' + cc(0x200F) + cc(0x202A) + '-' + cc(0x202E) + cc(0x2060) + '-' + cc(0x2064) +
    cc(0x2066) + '-' + cc(0x206F) + cc(0xFEFF) + cc(0x00AD) + cc(0x061C) + ']', 'g');
  var NBSP = new RegExp(cc(0x00A0), 'g');
  var TAG = 'ny-tr';
  var SID = Math.random().toString(36).slice(2, 10);

  var cfg = {};
  var seq = 0;
  var recs = {};          // id -> {id, el, t, st, fs, tr}
  var trByText = {};      // 原文 -> 譯文（本頁記憶體快取）
  var trByImg = {};       // 圖片網址 -> 圖中文字譯文（空字串＝圖中沒有文字）
  var queue = [];
  var MAX_REQUEST_ITEMS = 200;
  var flushTimer = 0;
  var scanTimer = 0;
  var lastHref = '';
  var reportedHref = '';
  var styleEls = [];      // 每個 root（document／shadow root）一個 <style>
  var styledRoots = new WeakSet();
  var observedRoots = new WeakSet();
  var ioByWin = new Map();
  var shadowHosts = [];
  var lastHostSearch = 0;
  var lastError = '';
  var lastScanAt = 0;

  function configure(c) {
    cfg = {
      roots: c.roots || [],
      shadow: !!c.shadow,
      iframes: !!c.iframes,
      paras: c.paras || 'p',
      minParas: c.minParas || 3,
      skip: c.skip || '',
      pathRe: c.pathRe ? new RegExp(c.pathRe) : null,
      mode: c.mode || cfg.mode || 'dual',
      auto: c.auto !== undefined ? !!c.auto : (cfg.auto !== undefined ? cfg.auto : true),
      scale: c.scale || cfg.scale || 1,
      eink: c.eink !== undefined ? !!c.eink : !!cfg.eink,
      ahead: c.ahead || cfg.ahead || 2,
      images: c.images !== undefined ? !!c.images : !!cfg.images,
      ui: c.ui !== undefined ? !!c.ui : !!cfg.ui,
      font: c.font || cfg.font || 'inherit',
      scrollFix: c.scrollFix || null
    };
    refreshStyles();
    scheduleScan(50);
  }

  // ── 樣式（e-ink 友善：不靠顏色區分，用左側直線＋縮排；待翻用虛線、失敗用雙線） ──
  function css() {
    var s = TAG + '{display:block!important;margin:.35em 0 .25em 0!important;padding:0 0 0 .6em!important;' +
      'border-left:' + (cfg.eink ? '.22em' : '.16em') + ' solid currentColor!important;' +
      'font-style:normal!important;white-space:pre-wrap!important;text-align:left!important;' +
      'text-decoration:none!important;letter-spacing:normal!important;word-break:break-word!important;' +
      'line-height:1.7!important;color:inherit!important;background:none!important;' +
      'visibility:visible!important;max-height:none!important;overflow:visible!important;}' +
      TAG + '.ny-pending{border-left-style:dashed!important;}' +
      TAG + '.ny-img{margin:.4em 0 .8em 0!important;}' +
      // 跑馬燈譯文：單行、自己橫向捲（內容重複兩份、捲 -50% 接成無縫循環）
      TAG + '.ny-mq{white-space:nowrap!important;overflow:hidden!important;border-left:0!important;' +
      'padding-left:0!important;text-align:left!important;margin:.3em 0!important;}' +
      TAG + '.ny-mq .ny-mq-in{display:inline-block!important;white-space:nowrap!important;' +
      'animation:ny-marquee var(--ny-mq-dur,20s) linear infinite!important;}' +
      '@keyframes ny-marquee{from{transform:translateX(0)}to{transform:translateX(-50%)}}' +
      '@media (prefers-reduced-motion: reduce){' + TAG + '.ny-mq .ny-mq-in{animation:none!important;}}' +
      TAG + '.ny-fail{border-left-style:double!important;border-left-width:.3em!important;}';
    if (cfg.eink) s += TAG + '{font-weight:500!important;}';
    // 譯文字型：網頁的韓文字型配中文常常很怪；generic 字族交給系統依 lang（繁中）挑字形
    var fam = { sans: 'sans-serif', serif: 'serif', custom: '"NekoYakuTr", sans-serif' }[cfg.font];
    if (fam) s += TAG + '{font-family:' + fam + '!important;}';
    if (cfg.mode === 'src') s += TAG + '{display:none!important;}';
    if (cfg.mode === 'tr') {
      // 只看譯文：原文縮成 0 並隱藏，譯文照常顯示（visibility 可以被子元素蓋回來）
      s += '[data-ny-src]{font-size:0!important;line-height:0!important;visibility:hidden!important;}' +
        '[data-ny-src]>' + TAG + '{border-left:0!important;padding-left:0!important;margin:0 0 .4em 0!important;' +
        'text-align:inherit!important;}' +
        '[data-ny-src]>' + TAG + '.ny-pending,[data-ny-src]>' + TAG + '.ny-fail{border-left:.16em dashed currentColor!important;padding-left:.6em!important;}';
    }
    return s;
  }
  function refreshStyles() {
    var text = css();
    styleEls = styleEls.filter(function (st) { return st.isConnected || st.__nyRoot; });
    styleEls.forEach(function (st) {
      st.textContent = fontFace(st.__nyRoot) + text;
      if (!st.isConnected && st.__nyRoot) attachStyle(st.__nyRoot, st);
    });
    Object.keys(recs).forEach(function (id) { var r = recs[id]; if (r.node) sizeNode(r); });
  }
  /** 自訂字型的 @font-face 只能放在 document（shadow root 裡宣告無效）；字檔由 App 從同源路徑提供。 */
  function fontFace(root) {
    return cfg.font === 'custom' && root.nodeType === 9
      ? '@font-face{font-family:"NekoYakuTr";src:url("/__nekoyaku_font__/tr")}' : '';
  }
  function attachStyle(root, st) {
    try {
      if (root.nodeType === 9) (root.head || root.documentElement).appendChild(st);
      else root.appendChild(st);
    } catch (e) {}
  }
  function ensureStyle(root) {
    if (!root || styledRoots.has(root)) {
      // 網頁有時整個換掉 head：樣式被拔掉就補回去
      for (var i = 0; i < styleEls.length; i++) {
        if (styleEls[i].__nyRoot === root && !styleEls[i].isConnected) attachStyle(root, styleEls[i]);
      }
      return;
    }
    styledRoots.add(root);
    var doc = root.nodeType === 9 ? root : root.ownerDocument;
    var st = doc.createElement('style');
    st.setAttribute('data-ny-style', '1');
    st.__nyRoot = root;
    st.textContent = fontFace(root) + css();
    styleEls.push(st);
    attachStyle(root, st);
  }

  // ── 文字 ──
  function textOf(el) {
    var out = '';
    (function walk(n) {
      for (var c = n.firstChild; c; c = c.nextSibling) {
        if (c.nodeType === 3) out += c.nodeValue;
        else if (c.nodeType === 1) {
          var t = c.tagName;
          if (t === 'NY-TR' || t === 'RT' || t === 'RP' || t === 'SCRIPT' || t === 'STYLE' || t === 'NOSCRIPT') continue;
          if (t === 'BR') { out += '\n'; continue; }
          walk(c);
        }
      }
    })(el);
    return out.replace(INVIS, '').replace(NBSP, ' ').replace(/[ \t\r\f]+/g, ' ')
      .replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  // ── 找正文 ──
  function frames() {
    var out = [{ doc: document, win: window }];
    if (!cfg.iframes) return out;
    (function walk(doc, depth) {
      if (depth > 3) return;
      var fs = doc.querySelectorAll('iframe');
      for (var i = 0; i < fs.length; i++) {
        try {
          var d = fs[i].contentDocument, w = fs[i].contentWindow;
          if (d && w && d.body) { out.push({ doc: d, win: w }); walk(d, depth + 1); }
        } catch (e) { /* 跨源 iframe：碰不到，略過 */ }
      }
    })(document, 0);
    return out;
  }
  function findShadowRoots(doc) {
    var now = Date.now();
    if (shadowHosts.length && now - lastHostSearch < 3000) {
      return shadowHosts.filter(function (h) { return h.isConnected && h.shadowRoot; }).map(function (h) { return h.shadowRoot; });
    }
    lastHostSearch = now;
    var hosts = [];
    var all = doc.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var s = all[i].shadowRoot;
      if (!s) continue;
      hosts.push(all[i]);
      var inner = s.querySelectorAll('*');
      for (var j = 0; j < inner.length; j++) if (inner[j].shadowRoot) hosts.push(inner[j]);
    }
    shadowHosts = hosts;
    return hosts.map(function (h) { return h.shadowRoot; });
  }
  function bases() {
    var out = [];
    frames().forEach(function (f) {
      var found = false;
      (cfg.roots || []).forEach(function (sel) {
        try {
          var r = f.doc.querySelectorAll(sel);
          for (var i = 0; i < r.length; i++) { out.push(r[i]); found = true; }
        } catch (e) {}
      });
      if (cfg.shadow) findShadowRoots(f.doc).forEach(function (s) { out.push(s); found = true; });
      // 沒指定容器的站（bomtoon）掃整個 body；指定了容器卻找不到時，只在同源 iframe 裡退回 body
      // （正文 render 前別把首頁選單、留言當正文）
      if (!found && !cfg.shadow && f.doc.body && (!cfg.roots.length || f.doc !== document)) out.push(f.doc.body);
    });
    return out;
  }
  function skipped(el) {
    if (!cfg.skip) return false;
    try { return !!el.closest(cfg.skip); } catch (e) { return false; }
  }
  /** 一個容器裡的正文段落：候選 selector 中「最內層、含韓文」的元素。 */
  function leaves(root) {
    var c;
    try { c = root.querySelectorAll(cfg.paras); } catch (e) { return []; }
    var cand = [];
    for (var i = 0; i < c.length; i++) {
      var el = c[i];
      if (el.closest && el.closest(TAG)) continue;
      if (!HANGUL.test(el.textContent || '')) continue;
      if (skipped(el)) continue;
      cand.push(el);
    }
    var set = new Set(cand), inner = new Set();
    cand.forEach(function (el) {
      for (var p = el.parentElement; p && p !== root; p = p.parentElement) {
        if (set.has(p)) inner.add(p);
      }
    });
    return cand.filter(function (el) { return !inner.has(el); });
  }

  function onChapter() {
    if (!cfg.pathRe) return true;
    try { return cfg.pathRe.test(location.pathname); } catch (e) { return true; }
  }

  // ── 掃描 ──
  function scheduleScan(ms) {
    if (scanTimer) return;
    scanTimer = setTimeout(function () { scanTimer = 0; try { scan(); } catch (e) { lastError = String(e && e.stack || e); } }, ms || 400);
  }
  function scan() {
    lastScanAt = Date.now();
    if (location.href !== lastHref) {
      lastHref = location.href;
      reportedHref = '';
      shadowHosts = [];
      lastHostSearch = 0;
    }
    // 丟掉已經不在畫面上的段落（SPA 換話後舊段落整批被換掉）
    Object.keys(recs).forEach(function (id) {
      var r = recs[id];
      if (!r.el.isConnected) { unobserve(r); delete recs[id]; }
    });
    var chapter = onChapter();
    var contentBases = chapter ? bases() : [];
    if (cfg.ui) { try { uiScan(contentBases); } catch (e) { lastError = String(e && e.stack || e); } }
    if (!chapter) return;
    var total = 0;
    contentBases.forEach(function (root) {
      var ls = leaves(root);
      if (ls.length < cfg.minParas) return;
      if (!paged && ls.length) detectPaged(ls[0]);
      watch(root.nodeType === 11 ? root : (root.ownerDocument || document).body || root);
      ls.forEach(function (el) { total++; consider(el); });
      if (cfg.images) images(root).forEach(considerImg);
    });
    if (total > 0 && reportedHref !== location.href) {
      reportedHref = location.href;
      var og = document.querySelector('meta[property="og:title"]');
      try {
        B.page(JSON.stringify({ sid: SID, url: location.href, title: document.title || '', og: og ? (og.getAttribute('content') || '') : '', count: total }));
      } catch (e) {}
    }
  }
  /**
   * 跑馬燈：整段是不是**同一句重複 N 次**（怪談 111화 的 box1bani 實測 115 次、2760 字）。
   * KMP failure function 求最小週期；不要求整除（結尾那份可能被截掉半截）。
   * 只送單位句去翻，不必請模型把同一句翻 115 次。回傳 {u: 單位句, n: 次數} 或 null。
   */
  function marqueeUnit(s) {
    var n = s.length;
    if (n < 60) return null;
    var f = new Array(n + 1), k = -1;
    f[0] = -1;
    for (var i = 1; i <= n; i++) {
      while (k >= 0 && s.charAt(k) !== s.charAt(i - 1)) k = f[k];
      k++;
      f[i] = k;
    }
    var p = n - f[n];
    if (p < 4 || p >= n) return null;
    var times = Math.ceil(n / p);
    if (times < 3) return null;
    var u = s.slice(0, p).trim();
    return u && HANGUL.test(u) ? { u: u, n: times } : null;
  }
  /**
   * 跑馬燈的譯文要放到「會捲動／不換行」的外框外面：塞在裡面會被撐成一大片（0.1.11 實機截圖）。
   * 往上找最外層有動畫或 nowrap 的祖先（最多 4 層），譯文插在它後面。
   */
  function escapeAnchor(el) {
    var out = el;
    for (var n = el, d = 0; n && n.nodeType === 1 && d < 4; n = n.parentElement, d++) {
      try {
        var cs = winOf(n).getComputedStyle(n);
        if ((cs.animationName && cs.animationName !== 'none') || /^(nowrap|pre)$/.test(cs.whiteSpace)) out = n;
      } catch (e) {}
    }
    return out;
  }
  function consider(el) {
    var full = textOf(el);
    if (!full || !HANGUL.test(full)) return;
    var mq = marqueeUnit(full);
    var t = mq ? mq.u : full;
    var r = el.__nyId ? recs[el.__nyId] : null;
    if (r && r.el === el) {
      if (r.t === t) {
        if (r.node && !r.node.isConnected) { r.node = null; paint(r); }
        return;
      }
      // 同一個元素換了文字（網頁重用節點）：當新段落重來
      unobserve(r);
      if (r.node && r.node.parentNode) r.node.parentNode.removeChild(r.node);
      delete recs[r.id];
    }
    var id = ++seq;
    r = { id: id, el: el, t: t, st: 'new', node: null, fs: 0, marquee: mq ? mq.n : 0 };
    if (mq) r.anchor = escapeAnchor(el);
    // 字級要在標記 data-ny-src 之前量：「只看譯文」模式會把原文字級壓成 0
    try { r.fs = parseFloat(winOf(el).getComputedStyle(el).fontSize) || 0; } catch (e) { r.fs = 0; }
    recs[id] = r;
    el.__nyId = id;
    try { el.setAttribute('data-ny-src', '1'); } catch (e) {}
    ensureStyle(el.getRootNode ? el.getRootNode() : document);
    if (trByText[t] !== undefined) { r.st = 'done'; paint(r); return; }
    observe(r);
  }

  // ── 正文插圖（作者把特效文字、聊天畫面做成圖片） ──
  function imgSrc(img) { return img.currentSrc || img.src || ''; }
  /** 正文裡夠大的圖：小圖（行內 emoji 那種）不算。還沒載完的下一輪再看。 */
  function images(root) {
    var out = [], list;
    try { list = root.querySelectorAll('img'); } catch (e) { return out; }
    for (var i = 0; i < list.length; i++) {
      var img = list[i];
      if (!img.complete || !imgSrc(img)) continue;
      if (img.closest && img.closest(TAG)) continue;
      var w = img.naturalWidth || 0, h = img.naturalHeight || 0;
      var rw = 0;
      try { rw = img.getBoundingClientRect().width; } catch (e) {}
      if ((w >= 200 && h >= 60) || rw >= 150) out.push(img);
    }
    return out;
  }
  function considerImg(img) {
    var src = imgSrc(img);
    var r = img.__nyId ? recs[img.__nyId] : null;
    if (r && r.el === img) {
      if (r.src === src) {
        if (r.node && !r.node.isConnected) { r.node = null; paint(r); }
        return;
      }
      unobserve(r);
      if (r.node && r.node.parentNode) r.node.parentNode.removeChild(r.node);
      delete recs[r.id];
    }
    var id = ++seq;
    var fs = 16;
    try { fs = parseFloat(winOf(img).getComputedStyle(img.parentElement || img).fontSize) || 16; } catch (e) {}
    r = { id: id, el: img, kind: 'img', src: src, t: '', st: 'new', node: null, fs: fs };
    recs[id] = r;
    img.__nyId = id;
    ensureStyle(img.getRootNode ? img.getRootNode() : document);
    if (trByImg[src] !== undefined) { r.st = 'done'; paint(r); return; }
    observe(r);
  }
  /** blob:／data: 圖在網頁裡轉成 JPEG（同源、不受跨源限制）；一般網址交給 App 下載。 */
  function imgData(img) {
    var src = imgSrc(img);
    if (src.indexOf('blob:') !== 0 && src.indexOf('data:') !== 0) return '';
    try {
      var w = img.naturalWidth, h = img.naturalHeight, k = Math.min(1, 1280 / Math.max(w, h));
      var c = img.ownerDocument.createElement('canvas');
      c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(h * k));
      var g = c.getContext('2d');
      g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
      g.drawImage(img, 0, 0, c.width, c.height);
      return c.toDataURL('image/jpeg', 0.8);
    } catch (e) { return ''; }
  }
  function requestImg(r) {
    if (r.st !== 'new' && r.st !== 'fail') return;
    if (trByImg[r.src] !== undefined) { r.st = 'done'; paint(r); return; }
    r.st = 'pending';
    paint(r);
    try {
      B.image(JSON.stringify({ sid: SID, url: location.href, i: r.id, src: r.src, data: imgData(r.el) }));
    } catch (e) { r.st = 'fail'; paint(r, '無法連到 App'); }
  }
  function applyImg(sid, id, src, text) {
    if (sid !== SID) return;
    trByImg[src] = text || '';
    Object.keys(recs).forEach(function (k) {
      var r = recs[k];
      if (r.kind === 'img' && r.src === src) { r.st = 'done'; paint(r); }
    });
  }
  function failImg(sid, id, msg) {
    if (sid !== SID) return;
    var r = recs[id];
    if (r && r.st === 'pending') { r.st = 'fail'; paint(r, msg); }
  }

  // ── 翻頁模式（kakaopage「페이지」閱覽方式）──
  // 這種 viewer 的總頁數不會把插入的譯文算進去，翻到一半就跳到最後一頁（0.1.2～0.1.5 實機驗證，
  // 從網頁外部無法修正），所以偵測到就改成捲動模式（forceScroll）。
  var paged = false;
  /**
   * 翻頁模式跟疊翻不相容（kakaopage 的總頁數不隨譯文更新，0.1.5 實機確認）：
   * 站方把閱讀方式存在 localStorage，改成捲動再重新載入。同一個分頁只試一次，避免改不動時無限重載。
   */
  function forceScroll() {
    var fix = cfg.scrollFix;
    if (!fix || !fix.key) return false;
    var tried = '';
    try { tried = sessionStorage.getItem('__nyScrollFix') || ''; } catch (e) {}
    if (tried) {
      try { B.note(JSON.stringify({ sid: SID, url: location.href, kind: 'forceScrollFailed', detail: fix.key + '=' + localStorage.getItem(fix.key) })); } catch (e) {}
      return false;
    }
    try {
      sessionStorage.setItem('__nyScrollFix', '1');
      localStorage.setItem(fix.key, fix.value);
      B.note(JSON.stringify({ sid: SID, url: location.href, kind: 'forceScroll', detail: fix.key + '=' + fix.value }));
      setTimeout(function () { location.reload(); }, 300);
      return true;
    } catch (e) { return false; }
  }
  function detectPaged(el) {
    for (var n = el; n; ) {
      if (n.nodeType === 1) {
        try {
          var cs = winOf(n).getComputedStyle(n);
          var cols = (cs.columnWidth && cs.columnWidth !== 'auto') || (cs.columnCount && cs.columnCount !== 'auto');
          var wide = n.scrollWidth > n.clientWidth * 1.8 && n.clientWidth > 0 && /(hidden|clip)/.test(cs.overflowX);
          if (cols || wide) {
            paged = true;
            if (forceScroll()) return;
            try { B.note(JSON.stringify({ sid: SID, url: location.href, kind: 'paged' })); } catch (e) {}
            return;
          }
        } catch (e) {}
      }
      if (n.parentNode && n.parentNode.nodeType === 11) n = n.parentNode.host; else n = n.parentNode;
      if (n && n.nodeType === 9) n = null;
    }
  }
  /** 翻頁模式下，畫面外的頁被容器裁掉、IntersectionObserver 看不到：往後多排幾段先翻。 */
  function lookahead(id) {
    for (var k = id + 1, n = 0; n < 15 && k <= seq; k++) {
      var r = recs[k];
      if (!r) continue;
      n++;
      if (r.st === 'new') { if (r.kind === 'img') requestImg(r); else enqueue(r); }
    }
  }
  // ── 網站介面翻譯（選單、按鈕、作品頁等短字串；直接換字，關掉就還原） ──
  var uiNodes = {};       // id -> 文字節點
  var uiSeq = 0;
  var uiTr = {};          // 原文 -> 譯文
  var uiAsked = {};       // 原文 -> 1（已送出）
  var uiRetryAt = {};     // 原文 -> 可以重送的時間（失敗過的）
  var uiTimer = 0;
  var uiBatch = [];
  function uiSkip(p) {
    if (!p) return true;
    if (/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|CODE|PRE|NY-TR|TITLE)$/.test(p.tagName)) return true;
    if (p.isContentEditable) return true;
    return !!(p.closest && (p.closest(TAG) || p.closest('[data-ny-src]')));
  }
  function uiRoots() {
    var out = [];
    frames().forEach(function (f) {
      if (f.doc.body) out.push(f.doc.body);
      if (cfg.shadow) findShadowRoots(f.doc).forEach(function (s) { out.push(s); });
    });
    return out;
  }
  /** [exclude]＝章節頁的正文區：正文交給疊翻，介面翻譯一律不碰（換掉原文會讓段落 hash 對不上）。 */
  function uiScan(exclude) {
    exclude = exclude || [];
    uiRoots().forEach(function (root) {
      if (exclude.indexOf(root) >= 0) return;
      var doc = root.ownerDocument || root;
      var w = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), n;
      while ((n = w.nextNode())) {
        var v = n.nodeValue;
        if (!v || v.length > 300 || !HANGUL.test(v)) continue;
        if (n.__nyUi && n.__nyUi.tr === v) continue; // 已經換成譯文
        if (uiSkip(n.parentElement)) continue;
        if (exclude.some(function (b) { return b !== root && b.contains && b.contains(n); })) continue;
        var src = v.replace(INVIS, '').trim();
        if (!src) continue;
        if (!n.__nyUi || n.__nyUi.tr !== v) n.__nyUi = { orig: v, src: src, tr: null };
        if (uiTr[src] !== undefined) { uiPut(n); continue; }
        var id = ++uiSeq;
        uiNodes[id] = n;
        if (uiAsked[src] && uiRetryAt[src] && Date.now() >= uiRetryAt[src]) { delete uiAsked[src]; delete uiRetryAt[src]; }
        if (!uiAsked[src]) { uiAsked[src] = 1; uiBatch.push({ i: id, t: src }); }
      }
    });
    if (uiBatch.length && !uiTimer) uiTimer = setTimeout(uiFlush, 300);
  }
  function uiFlush() {
    uiTimer = 0;
    while (uiBatch.length) {
      var items = uiBatch.splice(0, 60);
      try { B.ui(JSON.stringify({ sid: SID, url: location.href, items: items })); } catch (e) {}
    }
  }
  function uiPut(n) {
    var m = n.__nyUi, tr = uiTr[m.src];
    if (!tr) return;
    var lead = m.orig.match(/^\s*/)[0], trail = m.orig.match(/\s*$/)[0];
    m.tr = lead + tr + trail;
    if (n.nodeValue !== m.tr) n.nodeValue = m.tr;
  }
  function uiApply(sid, list) {
    if (sid !== SID) return;
    (list || []).forEach(function (it) { uiTr[it.t] = it.tr; });
    Object.keys(uiNodes).forEach(function (id) {
      var n = uiNodes[id];
      if (n.isConnected && n.__nyUi && uiTr[n.__nyUi.src] !== undefined) uiPut(n);
      if (!n.isConnected || uiTr[n.__nyUi && n.__nyUi.src] !== undefined) delete uiNodes[id];
    });
  }
  function uiFail(sid, list) {
    if (sid !== SID) return;
    // 30 秒後才重送：斷網時每輪掃描都立刻重送整批，只會一直失敗、洗爆紀錄（0.1.9 實機）
    var at = Date.now() + 30000;
    (list || []).forEach(function (t) { uiRetryAt[t] = at; });
  }
  function uiRestore() {
    uiRoots().forEach(function (root) {
      var doc = root.ownerDocument || root;
      var w = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), n;
      while ((n = w.nextNode())) {
        if (n.__nyUi && n.__nyUi.tr && n.nodeValue === n.__nyUi.tr) n.nodeValue = n.__nyUi.orig;
        n.__nyUi = null;
      }
    });
    uiNodes = {};
  }

  // ── 進入畫面附近才送翻 ──
  function ioFor(win) {
    var io = ioByWin.get(win);
    if (io) return io;
    var IO = win.IntersectionObserver || window.IntersectionObserver;
    if (!IO) return null;
    var m = Math.round(cfg.ahead * 100);
    io = new IO(function (entries) {
      entries.forEach(function (en) {
        if (!en.isIntersecting) return;
        var r = recs[en.target.__nyId];
        if (r && r.st === 'new' && cfg.auto) { if (r.kind === 'img') requestImg(r); else enqueue(r); }
        if (r && paged && cfg.auto) lookahead(r.id);
      });
    }, { rootMargin: '50% ' + m + '% ' + m + '% ' + m + '%' });
    ioByWin.set(win, io);
    return io;
  }
  function winOf(el) { var d = el.ownerDocument; return (d && d.defaultView) || window; }
  function observe(r) {
    var io = ioFor(winOf(r.el));
    if (io) io.observe(r.el); else if (cfg.auto) { if (r.kind === 'img') requestImg(r); else enqueue(r); }
  }
  function unobserve(r) {
    var io = ioByWin.get(winOf(r.el));
    if (io) { try { io.unobserve(r.el); } catch (e) {} }
  }
  function enqueue(r) {
    if (r.st !== 'new' && r.st !== 'fail') return;
    if (trByText[r.t] !== undefined) { r.st = 'done'; paint(r); return; }
    r.st = 'queued';
    queue.push(r);
    if (!flushTimer) flushTimer = setTimeout(flush, 350);
  }
  function flush() {
    flushTimer = 0;
    var batch = queue.filter(function (r) { return r.st === 'queued' && r.el.isConnected; });
    queue = [];
    batch.sort(function (a, b) { return a.id - b.id; });
    // 電子書型一話可達數千段；App 單次請求有上限（MAX_ITEMS_PER_REQUEST），超過會被丟棄
    for (var i = 0; i < batch.length; i += MAX_REQUEST_ITEMS) sendBatch(batch.slice(i, i + MAX_REQUEST_ITEMS));
  }
  function sendBatch(batch) {
    var items = [], seen = {};
    batch.forEach(function (r) {
      r.st = 'pending';
      paint(r);
      if (!seen[r.t]) { seen[r.t] = 1; items.push({ i: r.id, t: r.t }); }
    });
    // 前文脈絡：送出的第一段前面兩段原文（韓文常省主詞，沒有前文模型只能猜）
    var ctx = [];
    for (var k = batch[0].id - 1; k > 0 && ctx.length < 2; k--) {
      if (recs[k] && recs[k].el.isConnected) ctx.unshift(recs[k].t);
    }
    try {
      B.request(JSON.stringify({ sid: SID, url: location.href, items: items, ctx: ctx }));
    } catch (e) {
      batch.forEach(function (r) { r.st = 'fail'; paint(r, '無法連到 App'); });
    }
  }

  // ── 畫譯文 ──
  function sizeNode(r) {
    if (!r.fs) r.fs = 16;
    r.node.style.setProperty('font-size', (r.fs * cfg.scale).toFixed(1) + 'px', 'important');
    colorNode(r);
  }
  /** 原文現在的字級。「只看譯文」的 CSS 把原文壓成 0，要暫時拿掉標記才量得到（同步量完放回，不會畫出來）。 */
  function srcFontSize(r) {
    var el = r.kind === 'img' ? r.el.parentElement || r.el : r.el;
    var marked = el.hasAttribute('data-ny-src');
    if (marked) el.removeAttribute('data-ny-src');
    var fs = 0;
    try { fs = parseFloat(winOf(el).getComputedStyle(el).fontSize) || 0; } catch (e) {}
    if (marked) el.setAttribute('data-ny-src', '1');
    return fs;
  }
  /** 網頁閱讀器調字級（class、inline style 都有）：抽前幾段看有沒有變，變了才全部重量。 */
  function syncFontSizes() {
    var ids = Object.keys(recs);
    var changed = ids.slice(0, 5).some(function (id) {
      var fs = srcFontSize(recs[id]);
      return fs > 0 && Math.abs(fs - recs[id].fs) > 0.1;
    });
    if (!changed) return;
    ids.forEach(function (id) {
      var r = recs[id], fs = srcFontSize(r);
      if (fs > 0 && fs !== r.fs) { r.fs = fs; if (r.node) sizeNode(r); }
    });
  }

  // ── 譯文字色：跟背景對比不夠時換色 ──
  // kakaopage 的黑底電子看板（box1b）：段落本身是深色字，綠字是內層 span 自己的顏色；
  // 譯文照段落顏色會變成黑底深灰字，幾乎看不見（0.1.10 實機截圖）。
  function rgb(c) {
    var m = /rgba?\(([^)]+)\)/.exec(c || '');
    if (!m) return null;
    var p = m[1].split(',').map(parseFloat);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }
  function lum(c) {
    function ch(v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
  }
  function contrast(a, b) { var x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
  function upOf(n) { return n.parentNode && n.parentNode.nodeType === 11 ? n.parentNode.host : n.parentNode; }
  /** 元素實際看到的背景色：往上找第一個不透明的背景（跨 shadow root）；都沒有當白底。 */
  function bgOf(el) {
    for (var n = el; n && n.nodeType === 1; n = upOf(n)) {
      try {
        var c = rgb(winOf(n).getComputedStyle(n).backgroundColor);
        if (c && c.a > 0.5) return c;
      } catch (e) {}
    }
    return { r: 255, g: 255, b: 255, a: 1 };
  }
  /** 段落裡第一段文字實際的顏色（內層 span 自己設的那種）。 */
  function textColorOf(el) {
    var w = (el.ownerDocument || document).createTreeWalker(el, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) {
      var p = n.parentElement;
      if (!p || (p.closest && p.closest(TAG)) || !(n.nodeValue || '').trim()) continue;
      try { return rgb(winOf(p).getComputedStyle(p).color); } catch (e) { return null; }
    }
    return null;
  }
  function colorNode(r) {
    if (r.color === undefined) {
      r.color = '';
      try {
        // 以譯文實際所在的位置判斷背景（跑馬燈譯文在黑框外面，是白底）
        var host = r.node.parentElement || r.el;
        var bg = bgOf(host);
        var fg = rgb(winOf(host).getComputedStyle(host).color);
        if (fg && contrast(fg, bg) < 3) {
          var tc = r.kind === 'img' ? null : textColorOf(r.el);
          if (!cfg.eink && tc && contrast(tc, bg) >= 3) r.color = 'rgb(' + tc.r + ',' + tc.g + ',' + tc.b + ')';
          else r.color = lum(bg) > 0.4 ? '#000' : '#fff'; // 電子紙不用彩色：依背景深淺用黑或白
        }
      } catch (e) {}
    }
    if (r.color) r.node.style.setProperty('color', r.color, 'important');
    else r.node.style.removeProperty('color');
  }
  /** 譯文插在元素「後面」（圖片、跑馬燈外框）時，緊接在哪個元素之後；null＝放在段落裡面。 */
  function afterOf(r) { return r.kind === 'img' ? r.el : (r.marquee && r.anchor) ? r.anchor : null; }
  function placed(r) {
    if (!r.node || !r.node.isConnected) return false;
    var after = afterOf(r);
    return after ? r.node.previousSibling === after : r.node.parentNode === r.el;
  }
  function paint(r, msg) {
    var img = r.kind === 'img';
    var tr = img ? trByImg[r.src] : trByText[r.t];
    var drop = r.st === 'new' || r.st === 'queued' || (img && r.st === 'done' && !tr);
    if (drop) { if (r.node && r.node.parentNode) r.node.parentNode.removeChild(r.node); r.node = null; return; }
    if (!placed(r)) {
      if (r.node && r.node.parentNode) r.node.parentNode.removeChild(r.node);
      r.node = r.el.ownerDocument.createElement(TAG);
      r.node.__nyFor = r.id;
      // 網頁標成韓文時，中文字會被套上韓式字形；標成繁中讓系統挑對字形
      r.node.setAttribute('lang', 'zh-Hant-TW');
      r.node.addEventListener('click', onTrClick, true);
      // 圖片不能有子元素、跑馬燈外框會捲動：譯文放在它們正下方
      var after = afterOf(r);
      if (after) { if (!after.parentNode) return; after.parentNode.insertBefore(r.node, after.nextSibling); }
      else r.el.appendChild(r.node);
    }
    sizeNode(r);
    var cls = img ? 'ny-img' : '', text;
    if (r.st === 'done' && tr !== undefined) {
      // 跑馬燈：一般螢幕跟原文一起跑；電子紙不跑（一直重繪殘影很重），靜態標示重複次數
      if (r.marquee && !cfg.eink) { paintMarquee(r, tr); return; }
      text = img ? '〔圖中文字〕' + tr : r.marquee ? '〔跑馬燈〕' + tr + '（重複 ' + r.marquee + ' 次）' : tr;
    }
    else if (r.st === 'fail') { cls += ' ny-fail'; text = '⚠ ' + (img ? '圖片文字' : '') + '翻譯失敗' + (msg ? '（' + msg + '）' : '') + '，點這裡重試'; }
    else { cls += ' ny-pending'; text = img ? '⋯ 讀取圖中文字' : '⋯'; }
    cls = cls.trim();
    if (r.node.className !== cls) r.node.className = cls;
    if (r.node.textContent !== text) r.node.textContent = text;
  }
  function paintMarquee(r, tr) {
    if (r.node.className !== 'ny-mq') r.node.className = 'ny-mq';
    if (r.node.__nyMq === tr && r.node.firstChild) return;
    r.node.__nyMq = tr;
    // 單位句太短時多重複幾份，捲起來才不會中間出現空白；份數取偶數（捲 -50% 剛好接回開頭）
    var copies = Math.max(2, Math.ceil(60 / Math.max(1, tr.length)));
    if (copies % 2) copies++;
    var gap = String.fromCharCode(0x3000, 0x3000, 0x3000);
    var span = r.node.ownerDocument.createElement('span');
    span.className = 'ny-mq-in';
    span.textContent = new Array(copies + 1).join(tr + gap);
    span.style.setProperty('--ny-mq-dur', Math.max(8, Math.round((tr.length + 3) * copies / 2 * 0.35)) + 's');
    r.node.textContent = '';
    r.node.appendChild(span);
  }
  function onTrClick(ev) {
    var r = recs[this.__nyFor];
    if (!r || r.st !== 'fail') return;
    ev.stopPropagation();
    ev.preventDefault();
    r.st = 'new';
    if (r.kind === 'img') requestImg(r); else enqueue(r);
  }

  // ── 監看 DOM 變化 ──
  function ours(n) {
    if (!n) return false;
    if (n.nodeType === 1 && (n.tagName === 'NY-TR' || n.hasAttribute && n.hasAttribute('data-ny-style'))) return true;
    var p = n.nodeType === 1 ? n : n.parentElement;
    return !!(p && p.closest && p.closest(TAG));
  }
  function watch(root) {
    if (!root || observedRoots.has(root)) return;
    observedRoots.add(root);
    var win = (root.ownerDocument && root.ownerDocument.defaultView) || window;
    var MO = win.MutationObserver || window.MutationObserver;
    var mo = new MO(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (ours(m.target)) continue; // 我們自己的譯文內容變動
        if (m.type === 'characterData' || m.removedNodes.length) { scheduleScan(400); return; } // 譯文被拔掉也要補回
        for (var j = 0; j < m.addedNodes.length; j++) {
          if (!ours(m.addedNodes[j])) { scheduleScan(400); return; }
        }
      }
    });
    mo.observe(root, { childList: true, subtree: true, characterData: true });
  }

  // ── App 呼叫的介面 ──
  function apply(sid, list) {
    if (sid !== SID) return 0;
    var n = 0;
    (list || []).forEach(function (it) {
      var src = it.t !== undefined ? it.t : (recs[it.i] ? recs[it.i].t : undefined);
      if (src === undefined) return;
      trByText[src] = it.tr;
    });
    Object.keys(recs).forEach(function (id) {
      var r = recs[id];
      if (r.kind !== 'img' && r.st !== 'done' && trByText[r.t] !== undefined) { r.st = 'done'; paint(r); n++; }
    });
    return n;
  }
  /** [auto]＝網路暫時斷線之類：20 秒後自動重送，同一段最多 3 次（之後要使用者點）。 */
  function fail(sid, ids, msg, auto) {
    if (sid !== SID) return;
    var bad = {};
    (ids || []).forEach(function (i) { if (recs[i]) bad[recs[i].t] = 1; });
    Object.keys(recs).forEach(function (id) {
      var r = recs[id];
      if (r.kind !== 'img' && bad[r.t] && r.st === 'pending') {
        r.st = 'fail';
        r.tries = (r.tries || 0) + 1;
        r.retryAt = auto && r.tries <= 3 ? Date.now() + 20000 : 0;
        paint(r, r.retryAt ? (msg ? msg + '，稍後自動重試' : '稍後自動重試') : msg);
      }
    });
  }
  function autoRetry() {
    var now = Date.now();
    Object.keys(recs).forEach(function (id) {
      var r = recs[id];
      if (r.st === 'fail' && r.retryAt && now >= r.retryAt && r.el.isConnected) { r.retryAt = 0; r.st = 'new'; enqueue(r); }
    });
  }
  function collect() {
    var seen = {}, out = [];
    Object.keys(recs).map(Number).sort(function (a, b) { return a - b; }).forEach(function (id) {
      var r = recs[id];
      if (r.kind !== 'img' && r.el.isConnected && !seen[r.t]) { seen[r.t] = 1; out.push(r.t); }
    });
    return JSON.stringify(out);
  }
  function translateAll() {
    Object.keys(recs).map(Number).sort(function (a, b) { return a - b; }).forEach(function (id) {
      var r = recs[id];
      if (r.st === 'new' || r.st === 'fail') { r.st = 'new'; if (r.kind === 'img') requestImg(r); else enqueue(r); }
    });
  }
  function reset() {
    trByText = {};
    trByImg = {};
    Object.keys(recs).forEach(function (id) {
      var r = recs[id];
      if (r.node && r.node.parentNode) r.node.parentNode.removeChild(r.node);
      r.node = null;
      r.st = 'new';
      unobserve(r);
      observe(r);
    });
  }
  /** 音量鍵翻頁：找出正文所在的捲動容器，捲一個畫面（不用平滑捲動，e-ink 少殘影）。 */
  /** html／body 的 overflow 會傳給視窗（html 沒設 overflow 時 body 的才會傳）：這時對它們捲動沒有作用，要捲視窗。 */
  function scrollsViewport(n) {
    var html = n.ownerDocument.documentElement;
    if (n === html) return true;
    return n === n.ownerDocument.body && winOf(n).getComputedStyle(html).overflowY === 'visible';
  }
  function page(dir) {
    var el = null;
    Object.keys(recs).some(function (id) { var r = recs[id]; if (r.el.isConnected) { el = r.el; return true; } return false; });
    var target = null;
    for (var n = el; n; ) {
      if (n.nodeType === 1) {
        var w = winOf(n), cs = w.getComputedStyle(n);
        if (n.scrollHeight > n.clientHeight + 20 && /(auto|scroll)/.test(cs.overflowY) && !scrollsViewport(n)) { target = n; break; }
      }
      if (n.parentNode && n.parentNode.nodeType === 11) n = n.parentNode.host; else n = n.parentNode;
      if (n && n.nodeType === 9) { n = null; }
    }
    if (target) { target.scrollBy(0, dir * target.clientHeight * 0.9); return 'el'; }
    var w2 = el ? winOf(el) : window;
    w2.scrollBy(0, dir * w2.innerHeight * 0.9);
    return 'win';
  }
  function stats() {
    var s = { total: 0, done: 0, pending: 0, fail: 0, err: lastError };
    Object.keys(recs).forEach(function (id) {
      var r = recs[id];
      if (!r.el.isConnected) return;
      s.total++;
      if (r.st === 'done') s.done++; else if (r.st === 'pending' || r.st === 'queued') s.pending++; else if (r.st === 'fail') s.fail++;
    });
    return JSON.stringify(s);
  }

  window.__ny = {
    sid: function () { return SID; },
    configure: function (c) { configure(c || {}); },
    setMode: function (m) { cfg.mode = m; refreshStyles(); },
    setAuto: function (a) {
      cfg.auto = !!a;
      if (cfg.auto) { ioByWin.forEach(function (io) { io.disconnect(); }); ioByWin = new Map();
        Object.keys(recs).forEach(function (id) { if (recs[id].st === 'new') observe(recs[id]); }); }
    },
    setStyle: function (o) {
      if (o.scale) cfg.scale = o.scale;
      if (o.eink !== undefined) { cfg.eink = !!o.eink; Object.keys(recs).forEach(function (id) { recs[id].color = undefined; }); }
      refreshStyles();
    },
    apply: apply,
    applyImg: applyImg,
    failImg: failImg,
    fail: fail,
    collect: collect,
    translateAll: translateAll,
    reset: reset,
    page: page,
    stats: stats,
    rescan: function () { scheduleScan(10); },
    setUi: function (on) { cfg.ui = !!on; if (cfg.ui) scheduleScan(10); else uiRestore(); },
    uiApply: uiApply,
    uiFail: uiFail,
    debug: function () {
      return JSON.stringify({ onChapter: onChapter(), bases: bases().map(function (b) {
        return { node: b.nodeName, leaves: leaves(b).length };
      }), err: lastError });
    }
  };
  configure(cfgIn);
  // 正文還沒出現前密集一點找（SPA／shadow root 晚掛上不會觸發 MutationObserver），找到後放慢
  setInterval(function () {
    autoRetry();
    syncFontSizes();
    var gap = Date.now() - lastScanAt;
    if ((Object.keys(recs).length === 0 && onChapter()) || gap > (onChapter() ? 2000 : 1500)) scheduleScan(10);
  }, 700);
  return 'ok';
})();
