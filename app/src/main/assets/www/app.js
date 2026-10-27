// Arduino AI screen: TV-remote navigation, the code view, dialogs and the Claude actions.
const $ = (id) => document.getElementById(id);

// Opened in a normal browser (for testing), there is no Android bridge: use a stand-in.
const bridge = window.Android || {
  key: '',
  getKey() { return this.key; },
  setKey(k) { this.key = k; },
  isTv() { return false; },
  version() { return 'browser'; },
  checkUpdate() {},
  ask(id, task) {
    setTimeout(() => onAiResult(id, JSON.stringify({ ok: true, text: `(Test answer for "${task}")\nWire the LED to pin 13 through a 220 Ω resistor.`, code: task === 'explain' ? null : 'void setup() {\n  pinMode(13, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(13, HIGH);\n  delay(500);\n  digitalWrite(13, LOW);\n  delay(500);\n}\n' })), 800);
  },
  voice() { setTimeout(() => onVoice('blink an LED'), 300); },
  qr() { return ''; },
};

const LANGUAGES = ['English', 'Hindi', 'Marathi', 'Gujarati', 'Tamil', 'Telugu', 'Kannada', 'Bengali'];

const store = {
  get(k, fallback) { try { const v = localStorage.getItem(k); return v === null ? fallback : JSON.parse(v); } catch { return fallback; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};

const state = {
  code: store.get('code', EXAMPLES['Blink (any board)']),
  name: store.get('name', null),
  history: [],
  pending: null,
  modalOpen: false,
  modalClose: null,
};

// ---------- code view with simple C++ colouring ----------

const KEYWORDS = /^(if|else|for|while|do|switch|case|break|continue|return|const|static|volatile|struct|class|public|private|new|delete|true|false|nullptr|sizeof|typedef|enum|default|goto)$/;
const TYPES = /^(void|int|long|short|char|bool|boolean|byte|float|double|unsigned|signed|String|uint8_t|uint16_t|uint32_t|int8_t|int16_t|int32_t|size_t|word|auto)$/;
const CONSTS = /^(HIGH|LOW|INPUT|OUTPUT|INPUT_PULLUP|LED_BUILTIN|A\d|D\d+|T\d|WIFI_STA|WIFI_AP|WL_CONNECTED|Serial\d?|WiFi)$/;

function escapeHtml(s) { return s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])); }

function highlight(code) {
  const re = /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')|(^[ \t]*#\w+)|(\b\d+(?:\.\d+)?[uUlLfF]*\b|\b0x[0-9a-fA-F]+\b)|([A-Za-z_]\w*)(\s*\()?/gm;
  let out = '';
  let last = 0;
  for (let m; (m = re.exec(code));) {
    out += escapeHtml(code.slice(last, m.index));
    last = re.lastIndex;
    const [all, comment, str, pre, num, word, paren] = m;
    if (comment) out += `<span class="c">${escapeHtml(comment)}</span>`;
    else if (str) out += `<span class="s">${escapeHtml(str)}</span>`;
    else if (pre) out += `<span class="p">${escapeHtml(pre)}</span>`;
    else if (num) out += `<span class="n">${num}</span>`;
    else if (word) {
      const cls = KEYWORDS.test(word) ? 'k' : TYPES.test(word) ? 't' : CONSTS.test(word) ? 'n' : paren ? 'f' : '';
      out += (cls ? `<span class="${cls}">${word}</span>` : word) + (paren ? escapeHtml(paren) : '');
    } else out += escapeHtml(all);
  }
  return out + escapeHtml(code.slice(last));
}

function setCode(code, { remember = true, name } = {}) {
  if (remember && code !== state.code) {
    state.history.push({ code: state.code, name: state.name });
    if (state.history.length > 30) state.history.shift();
  }
  state.code = code;
  if (name !== undefined) state.name = name;
  store.set('code', state.code);
  store.set('name', state.name);
  render();
}

function render() {
  $('code').innerHTML = highlight(state.code);
  $('sketchName').textContent = state.name || 'New sketch';
  $('btnUndo').disabled = !state.history.length;
}

function showAnswer(text, isError) {
  const el = $('answer');
  el.innerHTML = '';
  for (const para of text.split(/\n{2,}/)) {
    const p = document.createElement('p');
    if (isError) p.className = 'err';
    // Keep line breaks and show `inline code` as code.
    p.innerHTML = escapeHtml(para.trim()).replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/\n/g, '<br>');
    el.appendChild(p);
  }
  el.scrollTop = 0;
}

// ---------- TV remote navigation ----------
// Arrow keys move the highlight to the nearest button/field in that direction.
// Inside a text box, left/right move the cursor; up/down leave the box.

function focusables() {
  const root = state.modalOpen ? $('modal') : !$('busy').classList.contains('hidden') ? $('busy') : document.body;
  return [...root.querySelectorAll('.nav')].filter((el) => !el.disabled && el.offsetParent !== null
    && !(root === document.body && el.closest('.overlay')));
}

function move(dir) {
  const items = focusables();
  const cur = document.activeElement;
  if (!items.includes(cur)) { items[0] && items[0].focus(); return true; }
  const a = cur.getBoundingClientRect();
  const ax = a.left + a.width / 2;
  const ay = a.top + a.height / 2;
  let best = null;
  let bestScore = Infinity;
  for (const el of items) {
    if (el === cur) continue;
    const b = el.getBoundingClientRect();
    const bx = b.left + b.width / 2;
    const by = b.top + b.height / 2;
    const dx = bx - ax;
    const dy = by - ay;
    let main;
    let side;
    if (dir === 'left') { if (b.right > a.left + 4) continue; main = -dx; side = Math.abs(dy); }
    else if (dir === 'right') { if (b.left < a.right - 4) continue; main = dx; side = Math.abs(dy); }
    else if (dir === 'up') { if (b.bottom > a.top + 4) continue; main = -dy; side = Math.abs(dx); }
    else { if (b.top < a.bottom - 4) continue; main = dy; side = Math.abs(dx); }
    const score = main + side * 2.5;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  if (best) { best.focus(); best.scrollIntoView({ block: 'nearest' }); return true; }
  return false;
}

document.addEventListener('keydown', (e) => {
  const dirs = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
  const dir = dirs[e.key];
  if (!dir) return;
  const el = document.activeElement;
  if (el && el.tagName === 'TEXTAREA') return; // the code editor uses arrows for the cursor
  if (el && el.tagName === 'SELECT') return;
  if (el && el.tagName === 'INPUT' && (dir === 'left' || dir === 'right')) {
    const atStart = el.selectionStart === 0 && el.selectionEnd === 0;
    const atEnd = el.selectionStart === el.value.length;
    if (!((dir === 'left' && atStart) || (dir === 'right' && atEnd))) return;
  }
  // Scrollable panes scroll with up/down first, then let the highlight move on.
  if (el && el.classList.contains('scroll') && (dir === 'up' || dir === 'down')) {
    const before = el.scrollTop;
    el.scrollTop += dir === 'down' ? 120 : -120;
    if (el.scrollTop !== before) { e.preventDefault(); return; }
  }
  if (move(dir)) e.preventDefault();
});

// The Android Back key: closes the open dialog first. Returns true when it handled the key.
window.handleBack = function handleBack() {
  if (state.modalOpen) { closeModal(); return true; }
  if (!$('busy').classList.contains('hidden')) { cancelAsk(); return true; }
  return false;
};

// ---------- dialogs ----------

function openModal(title, body, buttons, onClose) {
  state.modalOpen = true;
  state.modalClose = onClose || null;
  $('modalTitle').textContent = title;
  const bodyEl = $('modalBody');
  bodyEl.innerHTML = '';
  if (typeof body === 'string') { const p = document.createElement('p'); p.textContent = body; bodyEl.appendChild(p); } else bodyEl.appendChild(body);
  const btns = $('modalButtons');
  btns.innerHTML = '';
  for (const b of buttons) {
    const el = document.createElement('button');
    el.className = 'nav' + (b.primary ? ' primary' : '');
    el.textContent = b.label;
    el.onclick = () => { if (b.keepOpen) b.action(); else { closeModal(); if (b.action) b.action(); } };
    btns.appendChild(el);
  }
  $('modal').classList.remove('hidden');
  const first = bodyEl.querySelector('.nav') || btns.querySelector('.primary') || btns.querySelector('.nav');
  if (first) first.focus();
}

function closeModal() {
  if (!state.modalOpen) return;
  state.modalOpen = false;
  $('modal').classList.add('hidden');
  const cb = state.modalClose;
  state.modalClose = null;
  if (cb) cb();
  else $('btnWrite').focus();
}

function el(tag, attrs = {}, children = []) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e[k] = v;
    else e.setAttribute(k, v);
  }
  for (const c of children) e.appendChild(c);
  return e;
}

// ---------- Claude ----------

const BUSY_TEXT = {
  write: 'Claude is writing your code…',
  change: 'Claude is changing your code…',
  fix: 'Claude is looking for the problem…',
  explain: 'Claude is reading your code…',
};

function ask(task) {
  const message = $('message').value.trim();
  if (task === 'write' && !message) {
    openModal('What should it do?', 'Type or say (🎤) what you want your Arduino to do, then press “Write new code”.', [{ label: 'OK', primary: true }], () => $('message').focus());
    return;
  }
  if (task === 'change' && !message) {
    openModal('What should change?', 'Type or say how to change your code, e.g. “add a buzzer on pin 8 that beeps when the button is pressed”.', [{ label: 'OK', primary: true }], () => $('message').focus());
    return;
  }
  if (!bridge.getKey()) { openSettings(true); return; }
  const id = String(Date.now());
  state.pending = { id, task };
  $('busyText').textContent = BUSY_TEXT[task];
  $('busy').classList.remove('hidden');
  $('btnCancel').focus();
  bridge.ask(id, task, $('board').value, store.get('language', 'English'), message, state.code);
}

function cancelAsk() {
  state.pending = null;
  $('busy').classList.add('hidden');
  $('btnWrite').focus();
}

window.onAiResult = function onAiResult(id, json) {
  if (!state.pending || state.pending.id !== id) return; // cancelled
  const { task } = state.pending;
  state.pending = null;
  $('busy').classList.add('hidden');
  const r = JSON.parse(json);
  if (!r.ok) {
    showAnswer(r.error, true);
    $('answer').focus();
    return;
  }
  showAnswer(r.text || (r.code ? 'Done — the new code is on the right.' : ''), false);
  if (r.code) {
    const name = task === 'write' ? null : state.name;
    setCode(r.code, { name });
    if (task === 'write' || task === 'change') $('message').value = '';
    $('code').scrollTop = 0;
  }
  $('answer').focus();
};

// ---------- voice ----------

window.onVoice = function onVoice(text) {
  if (text) { $('message').value = text; $('btnWrite').focus(); } else $('message').focus();
};

window.onVoiceMissing = function onVoiceMissing() {
  openModal('Voice typing not available', 'This TV has no voice typing app. Type with the on-screen keyboard instead.', [{ label: 'OK', primary: true }], () => $('message').focus());
};

// ---------- sketches, examples, editing, QR ----------

function sketches() { return store.get('sketches', []); }

function saveSketch() {
  const input = el('input', { type: 'text', class: 'nav', value: state.name || '' });
  input.placeholder = 'Name, e.g. Room thermometer';
  const body = el('div', { class: 'field' }, [el('label', { text: 'Sketch name' }), input]);
  openModal('Save sketch', body, [
    { label: 'Cancel' },
    {
      label: 'Save', primary: true, action: () => {
        const name = input.value.trim() || `Sketch ${new Date().toLocaleString()}`;
        const list = sketches().filter((s) => s.name !== name);
        list.unshift({ name, board: $('board').value, code: state.code, time: Date.now() });
        store.set('sketches', list);
        state.name = name;
        store.set('name', name);
        render();
        $('btnSave').focus();
      },
    },
  ], () => $('btnSave').focus());
  input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); $('modalButtons').lastChild.click(); } };
}

function openSketches() {
  const list = sketches();
  const body = el('div', { class: 'list' });
  if (!list.length) body.appendChild(el('p', { class: 'muted', text: 'No saved sketches yet. Press “Save” to keep the code on the right.' }));
  for (const s of list) {
    const open = el('button', { class: 'nav grow', text: `${s.name}  ·  ${s.board || ''}` });
    open.onclick = () => {
      closeModal();
      setCode(s.code, { name: s.name });
      if (s.board) $('board').value = s.board;
      store.set('board', $('board').value);
      $('code').focus();
    };
    const del = el('button', { class: 'nav', text: 'Delete' });
    del.onclick = () => {
      store.set('sketches', sketches().filter((x) => x.name !== s.name));
      closeModal();
      openSketches();
    };
    body.appendChild(el('div', { class: 'row' }, [open, del]));
  }
  openModal('My sketches', body, [{ label: 'Close' }], () => $('btnSketches').focus());
}

function openExamples() {
  const body = el('div', { class: 'list' });
  for (const name of Object.keys(EXAMPLES)) {
    const b = el('button', { class: 'nav', text: name });
    b.onclick = () => { closeModal(); setCode(EXAMPLES[name], { name }); $('code').focus(); };
    body.appendChild(b);
  }
  openModal('Examples', body, [{ label: 'Close' }], () => $('btnExamples').focus());
}

function editCode() {
  const area = el('textarea', { id: 'editArea', class: 'nav', spellcheck: 'false' });
  area.value = state.code;
  openModal('Edit code', el('div', {}, [
    el('p', { class: 'muted', text: 'Arrow keys move the cursor here. Press Back or “Done” when finished.' }), area,
  ]), [
    // Cancel throws the edits away; Done and Back keep them.
    { label: 'Cancel', keepOpen: true, action: () => { area.value = state.code; closeModal(); } },
    { label: 'Done', primary: true },
  ], () => {
    if (area.value !== state.code) setCode(area.value);
    $('btnEdit').focus();
  });
}

function showQr() {
  const url = bridge.qr(state.code);
  if (!url) {
    openModal('Code too long for a QR code', `This sketch has ${state.code.length} characters; one QR code holds about 2,900. Save it and open it on a phone another way, or ask Claude for a shorter version.`, [{ label: 'OK', primary: true }], () => $('btnQr').focus());
    return;
  }
  openModal('Scan with your phone', el('div', {}, [
    el('img', { class: 'qr', src: url, alt: 'QR code of the sketch' }),
    el('p', { class: 'muted', text: 'Open the camera on your phone, point it at the code and copy the text. Paste it into the Arduino IDE on your computer to upload it to the board.' }),
  ]), [{ label: 'Close', primary: true }], () => $('btnQr').focus());
}

function openSettings(firstTime) {
  const key = el('input', { type: 'password', class: 'nav', value: bridge.getKey() });
  key.placeholder = 'sk-ant-…';
  const lang = el('select', { class: 'nav' });
  for (const l of LANGUAGES) lang.appendChild(el('option', { value: l, text: l }));
  lang.value = store.get('language', 'English');
  const body = el('div', {}, [
    el('p', { class: firstTime ? '' : 'muted', text: (firstTime ? 'To use Claude, add your API key first. ' : '') + 'Create a key at console.anthropic.com → API keys (needs an account with credit). It is stored only on this TV.' }),
    el('div', { class: 'field' }, [el('label', { text: 'Claude API key' }), key]),
    el('div', { class: 'field' }, [el('label', { text: 'Explanations in' }), lang]),
    el('p', { class: 'muted', text: `Version ${bridge.version()}` }),
  ]);
  openModal('Settings', body, [
    { label: 'Check for updates', action: () => bridge.checkUpdate() },
    { label: 'Cancel' },
    { label: 'Save', primary: true, action: () => { bridge.setKey(key.value); store.set('language', lang.value); } },
  ], () => $('btnSettings').focus());
}

// ---------- start ----------

function fillBoards() {
  const sel = $('board');
  const groups = {};
  for (const b of BOARDS) {
    if (!groups[b.group]) { groups[b.group] = el('optgroup', { label: b.group }); sel.appendChild(groups[b.group]); }
    groups[b.group].appendChild(el('option', { value: b.name, text: b.name }));
  }
  sel.value = store.get('board', 'Arduino Uno');
  if (!sel.value) sel.value = 'Arduino Uno';
  sel.onchange = () => store.set('board', sel.value);
}

document.querySelectorAll('[data-task]').forEach((b) => { b.onclick = () => ask(b.dataset.task); });
$('message').onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); $('btnWrite').focus(); } };
$('btnVoice').onclick = () => bridge.voice('What should your Arduino do?');
$('btnCancel').onclick = cancelAsk;
$('btnEdit').onclick = editCode;
$('btnUndo').onclick = () => {
  const prev = state.history.pop();
  if (prev) setCode(prev.code, { remember: false, name: prev.name });
  if (!state.history.length) $('btnEdit').focus();
};
$('btnSave').onclick = saveSketch;
$('btnNew').onclick = () => setCode('void setup() {\n  // runs once at the start\n\n}\n\nvoid loop() {\n  // runs again and again\n\n}\n', { name: null });
$('btnQr').onclick = showQr;
$('btnExamples').onclick = openExamples;
$('btnSketches').onclick = openSketches;
$('btnSettings').onclick = () => openSettings(false);

fillBoards();
render();
$('message').focus();
if (!bridge.getKey()) openSettings(true);
