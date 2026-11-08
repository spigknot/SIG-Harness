(function () {
  if (window.__sigVoiceHook) return;
  var bridge = window.SigVoice;
  if (!bridge || typeof bridge.start !== 'function') return;
  window.__sigVoiceHook = true;

  var state = 'idle';
  var text = '';
  var button = null;
  var bubble = null;
  var noticeTimer = 0;
  var menu = null;
  var pressTimer = 0;
  var suppressClick = false;

  var PROVIDERS = [
    { id: 'muse', label: 'Meta Muse Voice' },
    { id: 'gemini', label: 'Gemini 3.5 Transcribe' }
  ];

  var MIC_SVG = '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0"/><path d="M12 18v3"/></svg>';
  var STOP_SVG = '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2.5"/></svg>';
  var DOTS = '<span style="font-size:16px;line-height:1;letter-spacing:1px">…</span>';

  function installStyle() {
    if (document.getElementById('sig-voice-style')) return;
    var style = document.createElement('style');
    style.id = 'sig-voice-style';
    style.textContent =
      '#sig-voice-button{corner-shape:round;width:28px;height:28px;border-radius:999px;border:none;' +
      'background:var(--dsw-specific-selector);color:var(--dsw-alias-label-primary);cursor:pointer;' +
      'flex:none;display:grid;place-items:center;padding:0;margin:0;transition:background-color .1s}' +
      '#sig-voice-button:hover:not(:disabled){background:var(--dsw-alias-interactive-bg-hover-solid)}' +
      '#sig-voice-button[data-sig-state="listening"]{background:#d93b3b;color:#fff}' +
      '#sig-voice-button[data-sig-state="connecting"],#sig-voice-button[data-sig-state="finishing"]{opacity:.75}' +
      '#sig-voice-bubble{position:fixed;z-index:2147483000;max-width:min(72vw,340px);padding:6px 10px;' +
      'border-radius:12px;background:var(--dsw-alias-bg-overlay,rgba(28,28,30,.96));' +
      'color:var(--dsw-alias-label-primary,#f2f2f2);font:12.5px/1.35 -apple-system,Roboto,sans-serif;' +
      'box-shadow:0 6px 20px rgba(0,0,0,.35);display:none;pointer-events:none;white-space:nowrap;' +
      'overflow:hidden;text-overflow:ellipsis}';
    document.head.appendChild(style);
  }

  function currentProvider() {
    try { return String(bridge.sttProvider() || 'gemini'); } catch (error) { return 'gemini'; }
  }

  function closeMenu() {
    if (menu !== null && menu.parentNode !== null) menu.parentNode.removeChild(menu);
    menu = null;
  }

  /** Menu de provedores: mesmo formato do seletor de modelo/raciocínio. */
  function openMenu() {
    closeMenu();
    var active = currentProvider();
    var card = document.createElement('div');
    card.id = 'sig-voice-menu';
    // Estilo embutido: independe de folha de estilo e da ordem de carregamento.
    card.style.cssText = 'position:fixed;z-index:2147483000;min-width:214px;padding:6px;'
      + 'border-radius:12px;background:#26282d;border:1px solid rgba(255,255,255,.14);'
      + 'box-shadow:0 10px 30px rgba(0,0,0,.45);display:flex;flex-direction:column;gap:2px';
    card.setAttribute('role', 'menu');
    for (var i = 0; i < PROVIDERS.length; i++) {
      (function (provider) {
        var item = document.createElement('button');
        item.type = 'button';
        item.setAttribute('role', 'menuitem');
        item.style.cssText = 'display:flex;align-items:center;gap:8px;width:100%;text-align:left;'
          + 'border:0;border-radius:8px;padding:9px 10px;background:transparent;'
          + 'color:#f2f2f2;font:13px/1.25 -apple-system,Roboto,sans-serif;cursor:pointer'
          + (provider.id === active ? ';font-weight:600' : '');
        item.dataset.active = provider.id === active ? '1' : '0';
        item.textContent = provider.label;
        if (provider.id === active) {
          var check = document.createElement('span');
          check.className = 'sig-check';
          check.style.cssText = 'margin-left:auto;color:#4d6bfe;font-size:12px';
          check.textContent = '\u2713';
          item.appendChild(check);
        }
        item.addEventListener('click', function (event) {
          event.preventDefault();
          event.stopPropagation();
          try { bridge.setSttProvider(provider.id); } catch (error) { /* bridge ausente */ }
          closeMenu();
        });
        card.appendChild(item);
      })(PROVIDERS[i]);
    }
    document.body.appendChild(card);
    menu = card;
    var box = button.getBoundingClientRect();
    var left = Math.min(box.left, window.innerWidth - card.offsetWidth - 8);
    card.style.left = Math.max(8, left) + 'px';
    card.style.top = Math.max(8, box.top - card.offsetHeight - 8) + 'px';
    try {
      fetch('/api/sig/diag', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          version: '1.13.0', event: 'voice-menu-open',
          rect: [Math.round(card.getBoundingClientRect().left), Math.round(card.getBoundingClientRect().top),
                 card.offsetWidth, card.offsetHeight],
          items: card.childNodes.length, display: getComputedStyle(card).display
        })
      }).catch(function () {});
    } catch (error) { /* opcional */ }
  }

  /**
   * Toque longo delegado no documento (fase de captura): o composer chama
   * stopPropagation em ancestrais, então um listener no próprio botão nunca dispara.
   */
  function onGlobalPointerDown(event) {
    var target = event.target;
    if (target === null || typeof target.closest !== 'function') return;
    if (button === null || target.closest('#sig-voice-button') === null) return;
    var startX = event.clientX || 0;
    var startY = event.clientY || 0;
    if (pressTimer !== 0) window.clearTimeout(pressTimer);
    pressTimer = window.setTimeout(function () {
      pressTimer = 0;
      suppressClick = true;
      try { if (navigator.vibrate) navigator.vibrate(12); } catch (error) { /* sem vibração */ }
      openMenu();
      try { fetch('/api/sig/diag', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ version: '1.12.0', event: 'voice-longpress' }) }).catch(function () {}); } catch (error) { /* opcional */ }
    }, 480);
    var cancel = function (moveEvent) {
      if (moveEvent && (Math.abs((moveEvent.clientX || 0) - startX) > 12 || Math.abs((moveEvent.clientY || 0) - startY) > 12)) {
        if (pressTimer !== 0) { window.clearTimeout(pressTimer); pressTimer = 0; }
      }
    };
    window.addEventListener('pointermove', cancel, true);
    window.addEventListener('pointerup', function () {
      window.removeEventListener('pointermove', cancel, true);
      if (pressTimer !== 0) { window.clearTimeout(pressTimer); pressTimer = 0; }
    }, { capture: true, once: true });
  }

  document.addEventListener('pointerdown', onGlobalPointerDown, true);
  document.addEventListener('touchstart', onGlobalPointerDown, { capture: true, passive: true });
  function ensureNodes() {
    installStyle();
    if (button === null) {
      button = document.createElement('button');
      button.type = 'button';
      button.id = 'sig-voice-button';
      button.dataset.sigState = 'idle';
      button.setAttribute('aria-label', 'Ditar mensagem');
      button.innerHTML = MIC_SVG;
      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        if (suppressClick) { suppressClick = false; return; }
        try {
          if (state === 'listening' || state === 'connecting' || state === 'finishing') bridge.stop();
          else bridge.start();
        } catch (error) { /* bridge unavailable: keep idle */ }
      });
    }
    if (bubble === null) {
      bubble = document.createElement('div');
      bubble.id = 'sig-voice-bubble';
      bubble.setAttribute('role', 'status');
      document.body.appendChild(bubble);
    }
  }

  document.addEventListener('pointerdown', function (event) {
    if (menu === null) return;
    if (menu.contains(event.target) || event.target === button) return;
    closeMenu();
  }, true);

  function sendButton() {
    var card = document.querySelector('[data-composer-card]');
    if (card === null) return null;
    var labelled = card.querySelector(
      'button[aria-label="Send message"], button[aria-label="发送消息"],' +
      'button[aria-label="Steer message"], button[aria-label="插话发送"],' +
      'button[aria-label="Queue message"], button[aria-label="排队发送"],' +
      'button[aria-label="Stop generating"], button[aria-label="停止生成"]'
    );
    if (labelled !== null && labelled.offsetParent !== null) return labelled;
    var visible = [];
    var all = card.querySelectorAll('button');
    for (var i = 0; i < all.length; i++) {
      if (all[i].offsetParent !== null && !all[i].disabled) visible.push(all[i]);
    }
    return visible.length === 0 ? null : visible[visible.length - 1];
  }

  /** Keep the mic inside the composer row, directly before the send button. */
  function mount() {
    ensureNodes();
    var send = sendButton();
    if (send === null || send.parentNode === null) {
      if (button.parentNode !== null) button.parentNode.removeChild(button);
      return;
    }
    var row = send.parentNode;
    if (button.parentNode !== row || button.nextSibling !== send) {
      row.insertBefore(button, send);
    }
  }

  function placeBubble() {
    if (bubble === null || bubble.style.display !== 'block') return;
    var send = sendButton();
    if (send === null) return;
    var rect = send.getBoundingClientRect();
    bubble.style.right = Math.round(window.innerWidth - rect.right) + 'px';
    bubble.style.top = Math.round(Math.max(8, rect.top - 40)) + 'px';
  }

  function place() {
    mount();
    placeBubble();
  }

  function paint() {
    ensureNodes();
    button.dataset.sigState = state;
    if (state === 'listening') {
      button.innerHTML = STOP_SVG;
      button.setAttribute('aria-label', 'Parar ditado');
    } else if (state === 'connecting' || state === 'finishing') {
      button.innerHTML = DOTS;
      button.setAttribute('aria-label', 'Ditado em andamento');
    } else {
      button.innerHTML = MIC_SVG;
      button.setAttribute('aria-label', 'Ditar mensagem');
    }
    place();
  }

  function showBubble(value) {
    ensureNodes();
    var trimmed = (value || '').trim();
    if (trimmed === '') { bubble.style.display = 'none'; return; }
    bubble.textContent = trimmed;
    bubble.style.display = 'block';
    placeBubble();
  }

  function hideBubbleSoon(delay) {
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(function () { bubble.style.display = 'none'; }, delay || 0);
  }

  function notice(value) {
    ensureNodes();
    bubble.textContent = value;
    bubble.style.display = 'block';
    placeBubble();
    hideBubbleSoon(2600);
  }

  function composer() {
    var el = document.querySelector('[data-composer-input] [contenteditable="true"]');
    if (el === null) el = document.querySelector('[data-composer-input][contenteditable="true"]');
    if (el === null) el = document.querySelector('[data-composer-card] [contenteditable="true"]');
    return el;
  }

  function insertText(value) {
    var textValue = (value || '').trim();
    if (textValue === '') { notice('Nada transcrito.'); return; }
    var el = composer();
    if (el === null) { notice('Toque na caixa de texto e tente de novo.'); return; }
    el.focus();
    var selection = window.getSelection();
    if (selection !== null && (selection.rangeCount === 0 || !el.contains(selection.anchorNode))) {
      var range = document.createRange();
      range.selectNodeContents(el);
      range.collapse(false);
      selection.removeAllRanges();
      selection.addRange(range);
    }
    var before = el.textContent;
    var inserted = false;
    try { inserted = document.execCommand('insertText', false, textValue); } catch (error) { inserted = false; }
    if (!inserted || el.textContent === before) {
      try {
        var data = new DataTransfer();
        data.setData('text/plain', textValue);
        el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
      } catch (error) { /* ignore */ }
    }
  }

  window.SigVoiceOnState = function (next, detail) {
    state = next === 'listening' || next === 'connecting' || next === 'finishing' ? next : 'idle';
    paint();
    if (state === 'idle') { text = ''; hideBubbleSoon(600); }
  };
  window.SigVoiceOnPartial = function (value) {
    text = value || '';
    if (state === 'listening' || state === 'connecting') showBubble(text === '' ? 'Ouvindo…' : text);
  };
  window.SigVoiceOnFinal = function (value) {
    state = 'idle';
    paint();
    hideBubbleSoon(0);
    insertText(value);
  };
  window.SigVoiceOnError = function (message) {
    state = 'idle';
    paint();
    notice(message || 'Falha na transcrição.');
  };

  ensureNodes();
  paint();
  window.addEventListener('resize', place);
  window.addEventListener('orientationchange', function () { setTimeout(place, 250); });
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', place);
    window.visualViewport.addEventListener('scroll', place);
  }
  setInterval(place, 500);
  var observer = new MutationObserver(function () { place(); });
  try { observer.observe(document.body, { childList: true, subtree: true }); } catch (error) { /* ignore */ }
})();
