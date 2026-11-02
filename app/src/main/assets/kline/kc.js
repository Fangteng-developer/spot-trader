/* Kotlin <-> KLineChart bridge. Kotlin calls window.KC.*; JS calls window.Android.* (JavascriptInterface). */
(function () {
  var K = window.klinecharts, A = window.Android || { onReady: function () {}, onNeed: function () {}, onLineMoved: function () {}, onLineClick: function () {}, onDrawings: function () {}, onSelected: function () {}, log: function () {} };
  var UP = '#2EBD8E', DOWN = '#F2566B', TXT = '#E9EDF3', DIM = '#7E8797', GRID = '#1A202A', BG = '#090B10';
  var pp = 2, vp = 4, initCb = null, initData = null, barCb = null, pend = {}, seq = 0, lines = {}, dragging = null, drawTool = null, selected = null, compact = false, cur = '';

  // ---------- custom overlays ----------
  K.registerOverlay({ name: 'priceTag', totalStep: 2, needDefaultPointFigure: false, needDefaultXAxisFigure: false, needDefaultYAxisFigure: false,
    createPointFigures: function (p) { var c = p.coordinates[0], e = p.overlay.extendData || {}, w = p.bounding.width; if (!c) return [];
      var col = e.color || DIM, dash = e.dash ? [6, 4] : [];
      var txt = e.label + (e.drag ? '  ⇅' : ''); var out = [
        { type: 'line', attrs: { coordinates: [{ x: 0, y: c.y }, { x: w, y: c.y }] }, styles: { style: dash.length ? 'dashed' : 'solid', dashedValue: dash, color: col, size: e.width || 1 }, ignoreEvent: !e.drag && !e.click }];
      if (e.label) out.push({ type: 'text', attrs: { x: 6, y: c.y - 2, text: txt, align: 'left', baseline: 'bottom' }, styles: { color: '#fff', size: compact ? 10 : 11, backgroundColor: col, paddingLeft: 4, paddingRight: 4, paddingTop: 2, paddingBottom: 2, borderRadius: 2 }, ignoreEvent: !e.drag && !e.click });
      return out; },
    createYAxisFigures: function (p) { var c = p.coordinates[0], e = p.overlay.extendData || {}; if (!c) return [];
      var v = p.overlay.points[0].value; return [{ type: 'text', attrs: { x: 0, y: c.y, text: Number(v).toFixed(pp), align: 'left', baseline: 'middle' },
        styles: { color: e.fg || '#fff', size: compact ? 10 : 11, backgroundColor: e.color || DIM, paddingLeft: 4, paddingRight: 4, paddingTop: 3, paddingBottom: 3, borderRadius: 2 } }]; } });
  K.registerOverlay({ name: 'rectangle', totalStep: 3, needDefaultPointFigure: true,
    createPointFigures: function (p) { var c = p.coordinates; if (c.length < 2) return [];
      return [{ type: 'polygon', attrs: { coordinates: [c[0], { x: c[1].x, y: c[0].y }, c[1], { x: c[0].x, y: c[1].y }] }, styles: { style: 'stroke_fill', color: 'rgba(123,140,255,0.15)', borderColor: '#7B8CFF', borderSize: 1 } }]; } });
  K.registerOverlay({ name: 'textLabel', totalStep: 2, needDefaultPointFigure: true,
    createPointFigures: function (p) { var c = p.coordinates[0]; if (!c) return [];
      return [{ type: 'text', attrs: { x: c.x, y: c.y, text: String(p.overlay.extendData || 'Text'), align: 'left', baseline: 'bottom' }, styles: { color: '#E8B04B', size: 13, backgroundColor: 'rgba(0,0,0,0)' } }]; } });

  var chart = K.init('c', { locale: 'en-US', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone });
  function styles(type) { return {
    grid: { horizontal: { color: GRID, style: 'dashed' }, vertical: { color: GRID, style: 'dashed' } },
    candle: { type: type || 'candle_solid', bar: { upColor: UP, downColor: DOWN, noChangeColor: DIM, upBorderColor: UP, downBorderColor: DOWN, upWickColor: UP, downWickColor: DOWN },
      area: { lineColor: '#7B8CFF', backgroundColor: [{ offset: 0, color: 'rgba(123,140,255,0.01)' }, { offset: 1, color: 'rgba(123,140,255,0.25)' }] },
      priceMark: { last: { show: false }, high: { color: DIM }, low: { color: DIM } },
      tooltip: { text: { color: DIM, size: compact ? 10 : 11 } } },
    indicator: { tooltip: { text: { color: DIM, size: compact ? 10 : 11 } } },
    xAxis: { axisLine: { color: '#232A36' }, tickText: { color: DIM, size: 10 } },
    yAxis: { axisLine: { color: '#232A36' }, tickText: { color: DIM, size: 10 } },
    separator: { color: '#232A36' },
    crosshair: { horizontal: { line: { color: '#9AA3B2' }, text: { backgroundColor: '#E9EDF3', color: '#000' } }, vertical: { line: { color: '#9AA3B2' }, text: { backgroundColor: '#E9EDF3', color: '#000' } } },
    overlay: { line: { color: '#7B8CFF' }, text: { color: '#E8B04B' }, point: { color: '#7B8CFF', borderColor: 'rgba(123,140,255,0.35)', activeColor: '#7B8CFF', activeBorderColor: 'rgba(123,140,255,0.35)' } } }; }
  chart.setStyles(styles());

  chart.setDataLoader({
    getBars: function (q) {
      if (q.type === 'init') { if (initData) q.callback(initData, true); else initCb = q.callback; return; }
      if (q.type === 'forward') { var id = ++seq; pend[id] = q.callback; A.onNeed('older', String(q.timestamp || 0), id); return; }
      q.callback([], false); },
    subscribeBar: function (q) { barCb = q.callback; }, unsubscribeBar: function () { barCb = null; } });

  function per(iv) { var m = { '1m': ['minute', 1], '5m': ['minute', 5], '15m': ['minute', 15], '30m': ['minute', 30], '1h': ['hour', 1], '4h': ['hour', 4], '1d': ['day', 1], '1w': ['week', 1], '1M': ['month', 1] }[iv] || ['minute', 15];
    return { type: m[0], span: m[1] }; }
  function bars(a) { return a.map(function (k) { return { timestamp: k[0], open: k[1], high: k[2], low: k[3], close: k[4], volume: k[5] }; }); }
  function saveDrawings() { var o = chart.getOverlays({ groupId: 'draw' }).map(function (x) { return { name: x.name, points: x.points.map(function (p) { return { timestamp: p.timestamp, value: p.value }; }), extendData: x.extendData }; });
    A.onDrawings(JSON.stringify(o)); }
  function drawOpts(name, ext) { return { name: name, groupId: 'draw', extendData: ext,
    onDrawEnd: function () { drawTool = null; setTimeout(saveDrawings, 0); A.onSelected(''); return false; },
    onPressedMoveEnd: function () { setTimeout(saveDrawings, 0); return false; },
    onSelected: function (e) { selected = e.overlay.id; A.onSelected(e.overlay.id); return false; },
    onDeselected: function () { selected = null; A.onSelected(''); return false; },
    onRemoved: function () { setTimeout(saveDrawings, 0); return false; } }; }

  window.KC = {
    /** full (re)load: key=symbol, iv=interval, data=[[t,o,h,l,c,v],...] */
    load: function (key, iv, p, v, data) { pp = p; vp = v; var d = bars(data);
      if (initCb) { var cb = initCb; initCb = null; cb(d, true); } else initData = d;
      var same = cur === key + iv; cur = key + iv;
      if (same) chart.resetData(); else { chart.setSymbol({ ticker: key, pricePrecision: p, volumePrecision: v }); chart.setPeriod(per(iv)); } },
    resolve: function (id, data, more) { var cb = pend[id]; delete pend[id]; if (cb) cb(bars(data), { forward: !!more, backward: false }); },
    bar: function (k) { if (barCb) barCb({ timestamp: k[0], open: k[1], high: k[2], low: k[3], close: k[4], volume: k[5] }); },
    tick: function (price) { var l = chart.getDataList(); if (!l.length || !barCb) return; var b = l[l.length - 1];
      barCb({ timestamp: b.timestamp, open: b.open, high: Math.max(b.high, price), low: Math.min(b.low, price), close: price, volume: b.volume, turnover: b.turnover }); },
    setType: function (t) { chart.setStyles(styles(t)); },
    setCompact: function (c) { compact = !!c; chart.setStyles(styles()); },
    /** [{name, params:[], main:bool}] */
    setIndicators: function (list) { chart.removeIndicator(); list.forEach(function (x) {
        var o = { name: x.name }; if (x.params && x.params.length) o.calcParams = x.params;
        if (x.main) { o.paneId = 'candle_pane'; chart.createIndicator(o, true); } else { o.paneId = 'pane_' + x.name; chart.createIndicator(o, false); } }); },
    /** [{id,price,label,color,fg,dash,drag,click,width}] */
    setLines: function (list) { var seen = {};
      list.forEach(function (x) { seen[x.id] = 1; if (dragging === x.id) return;
        var ex = { label: x.label, color: x.color, fg: x.fg, dash: x.dash, drag: x.drag, click: x.click, width: x.width };
        var t = chart.getDataList(); var ts = t.length ? t[t.length - 1].timestamp : Date.now();
        if (lines[x.id]) { chart.overrideOverlay({ id: x.id, points: [{ timestamp: ts, value: x.price }], extendData: ex, lock: !x.drag }); return; }
        lines[x.id] = 1;
        chart.createOverlay({ id: x.id, name: 'priceTag', groupId: 'lines', lock: !x.drag, zLevel: 10, points: [{ timestamp: ts, value: x.price }], extendData: ex,
          onPressedMoveStart: function (e) { dragging = e.overlay.id; return false; },
          onPressedMoveEnd: function (e) { var id = e.overlay.id; dragging = null; A.onLineMoved(id, String(e.overlay.points[0].value)); return false; },
          onClick: function (e) { A.onLineClick(e.overlay.id); return false; } }); });
      Object.keys(lines).forEach(function (id) { if (!seen[id]) { chart.removeOverlay({ id: id }); delete lines[id]; } }); },
    /** MT5-style drawing tools */
    startDraw: function (name, ext) { drawTool = name; chart.createOverlay(drawOpts(name, ext)); },
    setDrawings: function (list) { chart.removeOverlay({ groupId: 'draw' }); (list || []).forEach(function (d) { var o = drawOpts(d.name, d.extendData); o.points = d.points; chart.createOverlay(o); }); },
    deleteSelected: function () { if (selected) { chart.removeOverlay({ id: selected }); selected = null; A.onSelected(''); saveDrawings(); } },
    clearDrawings: function () { chart.removeOverlay({ groupId: 'draw' }); saveDrawings(); },
    scrollLatest: function () { chart.scrollToRealTime(200); },
    resize: function () { chart.resize(); }
  };
  window.addEventListener('resize', function () { chart.resize(); });
  A.onReady();
})();
