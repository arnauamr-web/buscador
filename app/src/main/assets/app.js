'use strict';

/* ============ Funciones puras (se pueden probar fuera del móvil) ============ */

var STOP = {};
('el la los las un una unos unas de del al en con por para que es este esta esto ese esa eso ' +
 'mismo misma mismos mismas exacto exacta exactamente quiero busco buscar buscas ver imagen ' +
 'imagenes foto fotos video videos donde sale salen como desde sobre entre muy mas tal the and ' +
 'original lugar sitio persona cosa algo si no se lo le les su sus mi mis tu tus').split(' ')
  .forEach(function (w) { STOP[w] = 1; });

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}

function norm(s) {
  return String(s == null ? '' : s).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

// Solo enlaces web normales; nunca javascript: ni otros esquemas.
function safeUrl(u) {
  u = String(u == null ? '' : u).trim();
  return /^https?:\/\//i.test(u) ? u : '';
}

function safeImg(u) {
  u = String(u == null ? '' : u).trim();
  return /^(https?:\/\/|data:image\/)/i.test(u) ? u : '';
}

function host(u) {
  try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return ''; }
}

function keywords(text) {
  var seen = {}, out = [];
  norm(text).split(/[^a-z0-9]+/).forEach(function (w) {
    if (w.length >= 3 && !STOP[w] && !seen[w]) { seen[w] = 1; out.push(w); }
  });
  return out;
}

function platformOf(link) {
  try {
    var u = new URL(link);
    var h = u.hostname.replace(/^www\./, '').replace(/^m\./, '');
    var p = u.pathname.toLowerCase();
    if (h === 'youtu.be' || h === 'youtube.com' || /\.youtube\.com$/.test(h)) return 'YouTube';
    if (h === 'vimeo.com' || /\.vimeo\.com$/.test(h)) return 'Vimeo';
    if (h === 'tiktok.com' || /\.tiktok\.com$/.test(h)) return 'TikTok';
    if (h === 'dailymotion.com' || h === 'dai.ly') return 'Dailymotion';
    if (h === 'twitch.tv' || /\.twitch\.tv$/.test(h)) return 'Twitch';
    if (h === 'fb.watch') return 'Facebook';
    if ((h === 'facebook.com' || /\.facebook\.com$/.test(h)) && /\/(watch|reel|reels|videos)(\/|$|\?)/.test(p)) return 'Facebook';
    if (h === 'instagram.com' && /\/(reel|reels|tv)(\/|$)/.test(p)) return 'Instagram';
  } catch (e) { /* enlace raro: no es vídeo */ }
  return '';
}

// Los resultados de "misma imagen" pueden venir en exact_matches o en visual_matches.
function itemsOf(d) {
  if (!d) return [];
  if (Array.isArray(d.exact_matches)) return d.exact_matches;
  if (Array.isArray(d.visual_matches)) return d.visual_matches;
  return [];
}

function cleanList(arr) {
  var seen = {}, out = [];
  (arr || []).forEach(function (x) {
    if (!x) return;
    var link = safeUrl(x.link);
    if (!link || seen[link]) return;
    seen[link] = 1;
    out.push({
      title: x.title || '',
      link: link,
      source: x.source || host(link),
      thumb: safeImg(x.thumbnail || x.image || ''),
      date: x.date || ''
    });
  });
  return out;
}

function scoreOf(n, kws) {
  if (!kws.length) return 0;
  var hay = norm(n.title + ' ' + n.source + ' ' + n.link);
  var s = 0;
  kws.forEach(function (k) { if (hay.indexOf(k) >= 0) s++; });
  return s;
}

// Primero lo que más coincide con lo que dijo el usuario; el resto en su orden original.
function rank(list, kws) {
  var arr = list.map(function (n, i) { n.score = scoreOf(n, kws); n.i = i; return n; });
  arr.sort(function (a, b) { return (b.score - a.score) || (a.i - b.i); });
  return arr;
}

function friendlyError(msg) {
  var l = String(msg || '').toLowerCase();
  if (l.indexOf('invalid api') >= 0) return 'Una de tus claves no es correcta. Revísalas en Ajustes.';
  if (l.indexOf('out of searches') >= 0) return 'Se han acabado tus búsquedas gratis de este mes.';
  return String(msg || 'Algo ha fallado.');
}

/* ----- HTML de los resultados ----- */

function itemHtml(n) {
  var plat = platformOf(n.link);
  return '<a class="item" href="' + esc(n.link) + '">' +
    (n.thumb ? '<img loading="lazy" src="' + esc(n.thumb) + '" alt="">' : '<div class="noimg"></div>') +
    '<div class="t">' + esc(n.title || n.link) + '</div>' +
    '<div class="s">' + esc(n.source) + (n.date ? ' · ' + esc(n.date) : '') + '</div>' +
    (plat ? '<span class="plat">' + esc(plat) + '</span>' : '') +
    (n.score > 0 ? '<span class="hit">Coincide con lo que buscas</span>' : '') +
    '</a>';
}

function gridHtml(list) {
  return '<div class="grid">' + list.map(itemHtml).join('') + '</div>';
}

function blocksHtml(tbs, depth) {
  if (!Array.isArray(tbs) || depth > 4) return '';
  var h = '';
  tbs.forEach(function (b) {
    if (!b) return;
    if (b.type === 'paragraph') {
      h += '<p>' + esc(b.snippet) + '</p>';
    } else if (b.type === 'heading') {
      h += '<h3>' + esc(b.snippet) + '</h3>';
    } else if (b.type === 'list') {
      h += '<ul>' + (b.list || []).map(function (it) {
        return '<li>' + (it.title ? '<b>' + esc(it.title) + '</b> ' : '') + esc(it.snippet || '') +
          blocksHtml(it.text_blocks, depth + 1) + '</li>';
      }).join('') + '</ul>';
    } else if (b.type === 'expandable') {
      h += (b.title ? '<h3>' + esc(b.title) + '</h3>' : '') + blocksHtml(b.text_blocks, depth + 1);
    }
  });
  return h;
}

function overviewHtml(ov) {
  var a = ov && ov.ai_overview;
  if (!a || !Array.isArray(a.text_blocks) || !a.text_blocks.length) return '';
  var h = '<p class="note">Resumen automático de Google. Puede tener errores.</p>' + blocksHtml(a.text_blocks, 0);
  var refs = cleanList((a.references || []).map(function (r) { return r; })).slice(0, 6);
  if (refs.length) {
    h += '<h3>Fuentes</h3><ul class="refs">' + refs.map(function (r) {
      return '<li><a href="' + esc(r.link) + '">' + esc(r.title || r.link) + '</a> <span class="s">' + esc(r.source) + '</span></li>';
    }).join('') + '</ul>';
  }
  return h;
}

function contextHtml(c, topic) {
  if (!c) return '';
  var h = '', any = false;
  var kg = c.knowledge_graph;
  if (kg && (kg.description || kg.title)) {
    any = true;
    h += '<p><b>' + esc(kg.title || '') + '</b>' + (kg.type ? ' <span class="s">(' + esc(kg.type) + ')</span>' : '') + '</p>';
    if (kg.description) h += '<p>' + esc(kg.description) + '</p>';
  }
  var ab = c.answer_box;
  if (ab) {
    var t = ab.answer || ab.result || ab.snippet || (Array.isArray(ab.snippet_highlighted_words) ? ab.snippet_highlighted_words.join(' ') : '');
    if (t) { any = true; h += '<p>' + esc(t) + '</p>'; }
  }
  var org = (c.organic_results || []).filter(function (o) { return o && o.snippet && safeUrl(o.link); }).slice(0, 6);
  if (org.length) {
    any = true;
    h += '<ul class="refs">' + org.map(function (o) {
      return '<li><a href="' + esc(o.link) + '">' + esc(o.title || o.link) + '</a>' +
        (o.date ? ' <span class="s">' + esc(o.date) + '</span>' : '') +
        '<div>' + esc(o.snippet) + '</div></li>';
    }).join('') + '</ul>';
  }
  if (!any) return '';
  return '<p class="note">Esto es lo que dicen las páginas sobre «' + esc(topic || '') +
    '». No es un texto escrito por una IA.</p>' + h;
}

function youtubeSearch(q) {
  return 'https://www.youtube.com/results?search_query=' + encodeURIComponent(q);
}

function oldestDate(lists) {
  var best = null;
  lists.forEach(function (l) {
    l.forEach(function (n) {
      var t = Date.parse(n.date);
      if (n.date && !isNaN(t) && (best === null || t < best.t)) best = { t: t, raw: n.date };
    });
  });
  return best ? best.raw : '';
}

function buildResultHtml(d) {
  d = d || {};
  var meta = d.meta || {};
  var kws = keywords((meta.what || '') + ' ' + (meta.details || ''));

  var exact = rank(cleanList(itemsOf(d.exact)), kws);
  var exactLinks = {};
  exact.forEach(function (n) { exactLinks[n.link] = 1; });
  var sim = rank(cleanList((d.all && d.all.visual_matches) || []), kws)
    .filter(function (n) { return !exactLinks[n.link]; });

  var videos = exact.concat(sim).filter(function (n) { return platformOf(n.link); });
  var vidLinks = {};
  videos.forEach(function (n) { vidLinks[n.link] = 1; });
  var exactRest = exact.filter(function (n) { return !vidLinks[n.link]; }).slice(0, 30);
  var simRest = sim.filter(function (n) { return !vidLinks[n.link]; }).slice(0, 24);

  var out = [];

  // Resumen
  var counts = {};
  exact.concat(sim).forEach(function (n) { var h = host(n.link); if (h) counts[h] = (counts[h] || 0) + 1; });
  var tops = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; }).slice(0, 6);
  var s = '<section class="card"><h2>Resumen</h2>';
  if (meta.what) s += '<p>Es: <b>' + esc(meta.what) + '</b></p>';
  if (meta.details) s += '<p>Buscas: <b>' + esc(meta.details) + '</b></p>';
  if (meta.wantExact) {
    s += '<p>' + (exact.length
      ? 'Misma imagen encontrada en <b>' + exact.length + '</b> páginas.'
      : '<span class="warn">No he encontrado la misma imagen en internet.</span>') + '</p>';
  }
  if (sim.length) s += '<p>Imágenes parecidas: <b>' + sim.length + '</b></p>';
  var od = oldestDate([exact, sim]);
  s += '<p>Fecha más antigua que dan las páginas: <b>' + (od ? esc(od) : 'ninguna página la indica') + '</b></p>';
  if (tops.length) s += '<div class="row">' + tops.map(function (t) { return '<span class="tag">' + esc(t) + ' · ' + counts[t] + '</span>'; }).join('') + '</div>';
  s += '</section>';
  out.push(s);

  // Explicación (opcional)
  if (meta.wantExplain) {
    var ex = overviewHtml(d.overview) || contextHtml(d.context, d.contextTopic);
    out.push('<section class="card"><h2>Explicación</h2>' +
      (ex || '<p class="warn">No he podido preparar una explicación para esta imagen. Prueba a escribir qué es en el campo «¿Qué es?».</p>') +
      '</section>');
  }

  // La misma imagen
  if (meta.wantExact) {
    if (exactRest.length) {
      out.push('<section class="card"><h2>La misma imagen</h2>' + gridHtml(exactRest) + '</section>');
    }
  }

  // Vídeos
  if (videos.length) {
    out.push('<section class="card"><h2>Posibles vídeos de origen</h2>' + gridHtml(videos.slice(0, 12)) + '</section>');
  } else if (meta.wantExact) {
    var q = (meta.what || meta.details || (exact[0] && exact[0].title) || (sim[0] && sim[0].title) || '').trim();
    out.push('<section class="card"><h2>Vídeo</h2><p class="warn">No he encontrado el vídeo exacto de esta imagen.</p>' +
      (q ? '<a class="btn sec block" href="' + esc(youtubeSearch(q)) + '">Buscar «' + esc(q.slice(0, 40)) + '» en YouTube</a>' : '') +
      '</section>');
  }

  // Parecidas
  if (simRest.length) {
    out.push('<section class="card"><h2>' + (meta.fallback ? 'Como no hay ninguna igual, estas son las más parecidas' : 'Imágenes parecidas') +
      '</h2>' + gridHtml(simRest) + '</section>');
  } else if (meta.fallback && !exact.length) {
    out.push('<section class="card"><h2>Imágenes parecidas</h2><p class="warn">Tampoco he encontrado imágenes parecidas. Prueba con una versión más grande o sin recortar.</p></section>');
  }

  return out.join('');
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    esc: esc, norm: norm, safeUrl: safeUrl, safeImg: safeImg, host: host, keywords: keywords,
    platformOf: platformOf, itemsOf: itemsOf, cleanList: cleanList, rank: rank,
    friendlyError: friendlyError, buildResultHtml: buildResultHtml, overviewHtml: overviewHtml,
    contextHtml: contextHtml
  };
}

/* ============ Pantalla (solo dentro de la app) ============ */

if (typeof document !== 'undefined') {
  (function () {
    var $ = function (id) { return document.getElementById(id); };
    var chosen = null;
    var view = 'vForm';

    function load(k) { try { return localStorage.getItem(k) || ''; } catch (e) { return ''; } }
    function save(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* sin almacenamiento */ } }

    function show(name) {
      view = name;
      ['vForm', 'vProgress', 'vResult', 'vError'].forEach(function (id) { $(id).hidden = (id !== name); });
      window.scrollTo(0, 0);
    }

    function setMsg(text, cls) { var m = $('msg'); m.textContent = text || ''; m.className = 'msg' + (cls ? ' ' + cls : ''); }

    function state() {
      if (!window.Android) return null;
      try { return JSON.parse(window.Android.getState()); } catch (e) { return null; }
    }

    function showProgress(step) {
      $('step').textContent = step || 'Buscando…';
      if (view !== 'vProgress') show('vProgress');
    }

    function showResult() {
      var raw = '';
      try { raw = window.Android.getResult(); } catch (e) { raw = ''; }
      var d = null;
      try { d = JSON.parse(raw); } catch (e) { d = null; }
      if (!d) { showError('No se pudo leer el resultado. Inténtalo otra vez.'); return; }
      $('resultBody').innerHTML = buildResultHtml(d);
      try { window.Android.markSeen(); } catch (e) { /* nada */ }
      show('vResult');
    }

    function showError(msg) {
      $('errText').textContent = friendlyError(msg);
      try { window.Android.markSeen(); } catch (e) { /* nada */ }
      show('vError');
    }

    function showForm() {
      show('vForm');
      var s = state();
      $('last').hidden = !(s && s.state === 'done');
    }

    function poll() {
      var s = state();
      if (!s) return;
      if (s.state === 'running') { showProgress(s.step); return; }
      if (s.state === 'done' && !s.seen) { showResult(); return; }
      if (s.state === 'error' && !s.seen) { showError(s.error); return; }
      if (view === 'vProgress') showForm();
      $('last').hidden = !(s.state === 'done');
    }
    window.__poll = poll;

    // ----- ajustes -----
    $('k1').value = load('k_imgbb');
    $('k2').value = load('k_serp');
    if (!$('k1').value || !$('k2').value) $('cfg').open = true;
    $('saveKeys').onclick = function () {
      save('k_imgbb', $('k1').value.trim());
      save('k_serp', $('k2').value.trim());
      $('cfg').open = false;
      setMsg('Claves guardadas.', 'ok');
    };

    // ----- imagen -----
    $('pick').onclick = function () { $('file').click(); };
    $('file').onchange = function () {
      var f = $('file').files[0];
      if (!f) return;
      chosen = f;
      var p = $('preview');
      p.src = URL.createObjectURL(f);
      p.hidden = false;
      $('go').disabled = false;
      setMsg('');
    };

    function shrink(file) {
      return new Promise(function (resolve, reject) {
        var img = new Image();
        var u = URL.createObjectURL(file);
        img.onload = function () {
          var MAX = 1280;
          var w = img.naturalWidth, h = img.naturalHeight;
          var k = Math.min(1, MAX / Math.max(w, h));
          w = Math.round(w * k); h = Math.round(h * k);
          var c = document.createElement('canvas');
          c.width = w; c.height = h;
          c.getContext('2d').drawImage(img, 0, 0, w, h);
          URL.revokeObjectURL(u);
          resolve(c.toDataURL('image/jpeg', 0.85).split(',')[1]);
        };
        img.onerror = function () { URL.revokeObjectURL(u); reject(new Error('No se pudo leer la imagen.')); };
        img.src = u;
      });
    }

    // ----- buscar -----
    $('go').onclick = function () {
      var k1 = load('k_imgbb'), k2 = load('k_serp');
      if (!k1 || !k2) { $('cfg').open = true; setMsg('Primero guarda las dos claves en Ajustes.', 'err'); return; }
      if (!chosen) return;
      if (!window.Android) { setMsg('Esto solo funciona dentro de la app instalada.', 'err'); return; }
      if (!$('optExact').checked && !$('optSimilar').checked && !$('optExplain').checked) {
        $('optExact').checked = true;
      }
      $('go').disabled = true;
      setMsg('Preparando la imagen…');
      shrink(chosen).then(function (b64) {
        var job = {
          imgbbKey: k1, serpKey: k2, image: b64,
          what: $('what').value.trim(), details: $('details').value.trim(),
          wantExact: $('optExact').checked, wantSimilar: $('optSimilar').checked, wantExplain: $('optExplain').checked
        };
        var r = window.Android.startSearch(JSON.stringify(job));
        if (r === 'ok') { setMsg(''); showProgress('Preparando la búsqueda…'); }
        else if (r === 'busy') { setMsg('Ya hay una búsqueda en marcha.', 'err'); poll(); }
        else { setMsg('No se pudo empezar la búsqueda (' + r + ').', 'err'); }
      }).catch(function (e) {
        setMsg(e.message || String(e), 'err');
      }).then(function () { $('go').disabled = !chosen; });
    };

    $('cancel').onclick = function () { try { window.Android.cancel(); } catch (e) { /* nada */ } showForm(); };
    $('again').onclick = showForm;
    $('again2').onclick = showForm;
    $('last').onclick = showResult;

    document.addEventListener('visibilitychange', poll);
    window.addEventListener('focus', poll);
    setInterval(poll, 1500);
    showForm();
    poll();
  })();
}
