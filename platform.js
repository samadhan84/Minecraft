// The browser side of DhruvVishu: WebGL objects, input events, the on-screen keyboard, fonts, sound and saved
// worlds. The game itself (game.js, compiled from the same Kotlin code as the phone and PC versions) calls these.
var VC = {
  // ------------------------------------------------------------------ WebGL
  gl: null, o: [null], freeList: [], uniforms: {},
  add: function (obj) { var i = this.freeList.length ? this.freeList.pop() : this.o.length; this.o[i] = obj; return i; },
  free: function (i) { this.o[i] = null; this.freeList.push(i); },
  uniform: function (p, n) {
    var k = p + ':' + n;
    if (!(k in this.uniforms)) { var l = this.gl.getUniformLocation(this.o[p], n); this.uniforms[k] = l ? this.add(l) : 0; }
    return this.uniforms[k];
  },
  texImage: function (w, h, argb) {
    var px = new Uint8Array(w * h * 4);
    for (var i = 0; i < w * h; i++) {
      var c = argb[i];
      px[i * 4] = (c >> 16) & 255; px[i * 4 + 1] = (c >> 8) & 255; px[i * 4 + 2] = c & 255; px[i * 4 + 3] = (c >>> 24) & 255;
    }
    this.gl.texImage2D(this.gl.TEXTURE_2D, 0, this.gl.RGBA, w, h, 0, this.gl.RGBA, this.gl.UNSIGNED_BYTE, px);
  },

  // ------------------------------------------------------------------ canvas and frame loop
  canvas: null,
  width: function () { return this.canvas.width; },
  height: function () { return this.canvas.height; },
  scale: 1,
  resize: function () {
    var dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.scale = dpr;
    var w = Math.floor(this.canvas.clientWidth * dpr), h = Math.floor(this.canvas.clientHeight * dpr);
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
  },
  loop: function (f) { function step(t) { VC.resize(); f(t); requestAnimationFrame(step); } requestAnimationFrame(step); },

  // ------------------------------------------------------------------ input: events are queued and read each frame
  // Each event: [kind, id, x, y] with kind 0 touch start, 1 touch move, 2 touch end, 3 mouse move (dx, dy locked),
  // 4 mouse down (button), 5 mouse up, 6 wheel, 7 key down (code), 8 key up, 9 typed text, 10 pointer at (x, y).
  ev: [],
  push: function (k, id, x, y) { this.ev.push([k, id, x, y]); },
  evCount: function () { return this.ev.length; },
  evKind: function (i) { return this.ev[i][0]; },
  evId: function (i) { return this.ev[i][1]; },
  evX: function (i) { return this.ev[i][2]; },
  evY: function (i) { return this.ev[i][3]; },
  evText: function (i) { return String(this.ev[i][1]); },
  evClear: function () { this.ev.length = 0; },
  locked: function () { return document.pointerLockElement === this.canvas; },
  wantLock: false,
  lock: function (on) {
    this.wantLock = on;
    if (!on && this.locked()) document.exitPointerLock();
  },
  touchUsed: false,
  isTouch: function () { return this.touchUsed || ('ontouchstart' in window && navigator.maxTouchPoints > 0); },

  // Text boxes: the game reports where they are each frame; tapping one focuses a hidden input so the phone's
  // keyboard opens (browsers only allow that inside the tap itself).
  fields: [], field: null,
  setFields: function (list) { this.fields = list; },
  fieldAt: function (x, y) {
    for (var i = 0; i + 3 < this.fields.length; i += 4) {
      var f = this.fields;
      if (x >= f[i] && x < f[i] + f[i + 2] && y >= f[i + 1] && y < f[i + 1] + f[i + 3]) return true;
    }
    return false;
  },
  focusText: function (on) {
    if (on) { if (document.activeElement !== this.field) { this.field.value = ''; this.field.focus(); } }
    else if (document.activeElement === this.field) this.field.blur();
  },

  // ------------------------------------------------------------------ fonts
  font: null,
  fontSheet: function (chars, cell) {
    var cols = 16, rows = Math.ceil(chars.length / cols), cw = cell * 2;
    var c = document.createElement('canvas'); c.width = cols * cw; c.height = rows * cell;
    var g = c.getContext('2d');
    g.font = 'bold ' + Math.round(cell * 26 / 32) + 'px -apple-system, "Helvetica Neue", Arial, sans-serif';
    g.fillStyle = '#fff'; g.textBaseline = 'alphabetic';
    var widths = [], xs = [], ys = [];
    for (var i = 0; i < chars.length; i++) {
      var x = (i % cols) * cw, y = Math.floor(i / cols) * cell;
      g.fillText(chars[i], x + 1, y + Math.round(cell * 0.78));
      xs.push(x); ys.push(y); widths.push(g.measureText(chars[i]).width + 1);
    }
    var d = g.getImageData(0, 0, c.width, c.height).data;
    var px = new Int32Array(c.width * c.height);
    for (var j = 0; j < px.length; j++) px[j] = (d[j * 4 + 3] << 24) | (d[j * 4] << 16) | (d[j * 4 + 1] << 8) | d[j * 4 + 2];
    this.font = { px: px, w: c.width, h: c.height, xs: xs, ys: ys, widths: widths };
  },
  fontFill: function (out) { out.set(this.font.px); },

  // ------------------------------------------------------------------ sound (Web Audio)
  ac: null, sounds: {}, master: null, musicAt: 0, rainNode: null, rainGain: null,
  audioStart: function () {
    if (this.ac) { if (this.ac.state !== 'running') this.ac.resume(); return; }
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ac = new AC();
    this.master = this.ac.createGain(); this.master.connect(this.ac.destination);
    // iPhones start sound only after a tap: play a silent buffer now, inside it.
    var b = this.ac.createBufferSource(); b.buffer = this.ac.createBuffer(1, 1, 22050); b.connect(this.master); b.start(0);
    this.musicAt = 0;
  },
  audioReady: function () { return !!this.ac; },
  addSound: function (name, pcm, rate) { this.sounds[name] = { pcm: pcm, rate: rate, buf: null }; },
  buffer: function (s) {
    if (!s.buf) {
      var b = this.ac.createBuffer(1, s.pcm.length, s.rate), ch = b.getChannelData(0);
      for (var i = 0; i < s.pcm.length; i++) ch[i] = s.pcm[i] / 32768;
      s.buf = b;
    }
    return s.buf;
  },
  play: function (name, gain, pan, pitch) {
    if (!this.ac || this.ac.state !== 'running') return;
    var s = this.sounds[name]; if (!s) return;
    var src = this.ac.createBufferSource(); src.buffer = this.buffer(s); src.playbackRate.value = pitch;
    var g = this.ac.createGain(); g.gain.value = gain;
    src.connect(g);
    if (this.ac.createStereoPanner) { var p = this.ac.createStereoPanner(); p.pan.value = pan; g.connect(p); p.connect(this.master); }
    else g.connect(this.master);
    src.start();
  },
  setRain: function (v) {
    if (!this.ac) return;
    if (v > 0.01 && !this.rainNode && this.sounds.rain) {
      this.rainGain = this.ac.createGain(); this.rainGain.connect(this.master);
      this.rainNode = this.ac.createBufferSource(); this.rainNode.buffer = this.buffer(this.sounds.rain); this.rainNode.loop = true;
      this.rainNode.connect(this.rainGain); this.rainNode.start();
    }
    if (this.rainGain) this.rainGain.gain.value = v;
    if (v <= 0.01 && this.rainNode) { this.rainNode.stop(); this.rainNode = null; this.rainGain = null; }
  },
  // Music is made by the game a little at a time; this says how many seconds are still queued.
  musicQueued: function () { return this.ac ? Math.max(0, this.musicAt - this.ac.currentTime) : 99; },
  queueMusic: function (samples, n, rate, gain) {
    if (!this.ac || this.ac.state !== 'running') return;
    var b = this.ac.createBuffer(1, n, rate), ch = b.getChannelData(0);
    for (var i = 0; i < n; i++) ch[i] = samples[i] * gain;
    var src = this.ac.createBufferSource(); src.buffer = b; src.connect(this.master);
    var at = Math.max(this.musicAt, this.ac.currentTime + 0.05);
    src.start(at); this.musicAt = at + n / rate;
  },

  // ------------------------------------------------------------------ saved worlds and settings
  // Worlds live in the game's in-memory files while playing; they are copied to IndexedDB (the browser's own
  // storage) whenever the game saves, and loaded back from it before the game starts.
  db: null, files: {}, fileList: [],
  openDb: function (done) {
    var finish = function () { done(); };
    // Ask the browser to keep the saved worlds even when space runs low.
    try { if (navigator.storage && navigator.storage.persist) navigator.storage.persist(); } catch (e) {}
    try {
      var req = indexedDB.open('dhruvvishu', 1);
      req.onupgradeneeded = function () { req.result.createObjectStore('files'); };
      req.onerror = finish;
      req.onsuccess = function () {
        VC.db = req.result;
        var tx = VC.db.transaction('files', 'readonly'), st = tx.objectStore('files');
        var cur = st.openCursor();
        cur.onsuccess = function () {
          var c = cur.result;
          if (c) { VC.files[c.key] = new Int8Array(c.value); c.continue(); } else { VC.fileList = Object.keys(VC.files); finish(); }
        };
        cur.onerror = finish;
      };
    } catch (e) { finish(); }
  },
  storedCount: function () { return this.fileList.length; },
  storedPath: function (i) { return this.fileList[i]; },
  storedSize: function (i) { return this.files[this.fileList[i]].length; },
  storedFill: function (i, out) { out.set(this.files[this.fileList[i]]); },
  storedDone: function () { this.fileList = []; },
  store: function (path, bytes) {
    var copy = new Int8Array(bytes);
    this.files[path] = copy;
    if (this.db) try { this.db.transaction('files', 'readwrite').objectStore('files').put(copy.buffer, path); } catch (e) {}
  },
  keys: function () { return Object.keys(this.files).join('\n'); },
  unstore: function (path) {
    delete this.files[path];
    if (this.db) try { this.db.transaction('files', 'readwrite').objectStore('files').delete(path); } catch (e) {}
  },
  pref: function (k) { try { var v = localStorage.getItem('dv.' + k); return v === null ? '' : v; } catch (e) { return ''; } },
  setPref: function (k, v) { try { localStorage.setItem('dv.' + k, v); } catch (e) {} },

  // ------------------------------------------------------------------ start
  init: function () {
    this.canvas = document.getElementById('game');
    this.gl = this.canvas.getContext('webgl', { antialias: false, alpha: false, preserveDrawingBuffer: false });
    if (!this.gl) { document.getElementById('loading').textContent = 'Sorry, this browser cannot show 3D graphics (WebGL).'; return false; }
    this.field = document.getElementById('text');
    this.resize();
    var cv = this.canvas, self = this;
    function pos(t) { var r = cv.getBoundingClientRect(); return [(t.clientX - r.left) * self.scale, (t.clientY - r.top) * self.scale]; }
    function touches(kind) {
      return function (e) {
        e.preventDefault(); self.touchUsed = true; self.audioStart();
        for (var i = 0; i < e.changedTouches.length; i++) {
          var t = e.changedTouches[i], p = pos(t);
          self.push(kind, t.identifier, p[0], p[1]);
          if (kind === 2) self.focusText(self.fieldAt(p[0], p[1]));
        }
      };
    }
    cv.addEventListener('touchstart', touches(0), { passive: false });
    cv.addEventListener('touchmove', touches(1), { passive: false });
    cv.addEventListener('touchend', touches(2), { passive: false });
    cv.addEventListener('touchcancel', touches(2), { passive: false });
    cv.addEventListener('mousemove', function (e) {
      if (self.locked()) self.push(3, 0, e.movementX * self.scale, e.movementY * self.scale);
      else { var p = pos(e); self.push(10, 0, p[0], p[1]); }
    });
    cv.addEventListener('mousedown', function (e) {
      self.audioStart();
      if (self.wantLock && !self.locked() && cv.requestPointerLock) cv.requestPointerLock();
      var p = pos(e); self.push(10, 0, p[0], p[1]); self.push(4, e.button, p[0], p[1]);
      self.focusText(self.fieldAt(p[0], p[1]));
    });
    window.addEventListener('mouseup', function (e) { self.push(5, e.button, 0, 0); });
    cv.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    cv.addEventListener('wheel', function (e) { e.preventDefault(); self.push(6, 0, 0, e.deltaY > 0 ? -1 : 1); }, { passive: false });
    window.addEventListener('keydown', function (e) {
      if (document.activeElement === self.field) {
        if (e.key === 'Backspace') { self.push(7, 'Backspace', 0, 0); e.preventDefault(); }
        else if (e.key === 'Enter') { self.push(7, 'Enter', 0, 0); self.field.blur(); e.preventDefault(); }
        return;
      }
      self.audioStart();
      if (e.code === 'Tab' || e.code === 'Space' || e.code.indexOf('Arrow') === 0) e.preventDefault();
      if (!e.repeat) self.push(7, e.code, 0, 0);
    });
    window.addEventListener('keyup', function (e) { self.push(8, e.code, 0, 0); });
    this.field.addEventListener('input', function () {
      var v = self.field.value; if (v) { self.push(9, v, 0, 0); self.field.value = ''; }
    });
    document.addEventListener('visibilitychange', function () { self.push(11, document.hidden ? 1 : 0, 0, 0); });
    window.addEventListener('pagehide', function () { self.push(11, 1, 0, 0); if (self.onHide) self.onHide(); });
    return true;
  }
};
