(function () {
  if (window.__sigBlobDownloadHook) return;
  var bridge = window.SigDownload;
  if (!bridge || typeof bridge.begin !== 'function') return;
  window.__sigBlobDownloadHook = true;

  var blobUrls = new Map();
  var createObjectURL = URL.createObjectURL.bind(URL);
  URL.createObjectURL = function (object) {
    var url = createObjectURL(object);
    try {
      if (object && typeof object.size === 'number' && typeof object.slice === 'function') {
        blobUrls.set(url, object);
      }
    } catch (error) { /* not a blob: leave it alone */ }
    return url;
  };
  var revokeObjectURL = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function (url) {
    try { blobUrls.delete(url); } catch (error) { /* ignore */ }
    return revokeObjectURL(url);
  };

  function toBase64(bytes) {
    var text = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      text += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(text);
  }

  function saveBlob(blob, name) {
    try {
      bridge.begin(name, blob.type || 'application/octet-stream');
    } catch (error) {
      return;
    }
    var chunk = 256 * 1024;
    var offset = 0;
    function next() {
      if (offset >= blob.size) {
        try { bridge.end(); } catch (error) { /* ignore */ }
        return;
      }
      var slice = blob.slice(offset, offset + chunk);
      offset += chunk;
      var reader = new FileReader();
      reader.onload = function () {
        try {
          bridge.chunk(toBase64(new Uint8Array(reader.result)));
          next();
        } catch (error) {
          try { bridge.fail(String(error && error.message || error)); } catch (ignored) { /* ignore */ }
        }
      };
      reader.onerror = function () {
        try { bridge.fail('leitura do arquivo falhou'); } catch (ignored) { /* ignore */ }
      };
      reader.readAsArrayBuffer(slice);
    }
    next();
  }


  /**
   * Toque longo num anexo baixa direto, sem passar pelo visualizador:
   * o card é clicado (abre o visualizador), o link de download que já existe
   * dentro dele é acionado e a tela volta ao fechar.
   */
  var attachTimer = 0;
  var attachSuppress = false;

  function findDownload() {
    var direct = document.querySelector("a[download]");
    if (direct !== null) return direct;
    var labels = ["ownload", "Baixar", "baixar", "Salvar", "保存", "下载"];
    var nodes = document.querySelectorAll("button, a");
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.offsetParent === null) continue;
      var text = (node.getAttribute("aria-label") || "") + " " + (node.getAttribute("title") || "");
      for (var j = 0; j < labels.length; j++) {
        if (text.indexOf(labels[j]) >= 0) return node;
      }
    }
    return null;
  }

  function closeViewer() {
    var closers = ["Close", "Fechar", "Cancelar", "关闭", "取消"];
    var nodes = document.querySelectorAll("button[aria-label]");
    for (var i = 0; i < nodes.length; i++) {
      var label = nodes[i].getAttribute("aria-label") || "";
      for (var j = 0; j < closers.length; j++) {
        if (label === closers[j]) { nodes[i].click(); return true; }
      }
    }
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    return false;
  }

  function longPressDownload(card) {
    try {
      card.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    } catch (error) { /* segue */ }
    window.setTimeout(function () {
      var target = findDownload();
      var found = target !== null;
      if (found) { try { target.click(); } catch (error) { /* segue */ } }
      window.setTimeout(function () { if (found) closeViewer(); }, 260);
      try {
        fetch("/api/sig/diag", {
          method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({ version: "1.17.0", event: "attach-longpress", found: found, cardHtml: String(card.outerHTML || "").slice(0, 400) })
        }).catch(function () {});
      } catch (error) { /* opcional */ }
    }, 160);
  }

  /** Elemento que representa um arquivo: link do markdown, card de anexo ou imagem. */
  function attachCard(node) {
    var current = node;
    for (var depth = 0; depth < 6 && current !== null; depth++) {
      var cls = String(current.className || "");
      if (cls.indexOf("fileLink") >= 0) return current;
      if (cls.indexOf("fileMention") >= 0) return current;
      if (cls.indexOf("attachments") >= 0) return current;
      if (cls.indexOf("attachment") >= 0) return current;
      if (/[_-]file($|[A-Z_])/.test(cls)) return current;
      if (current.tagName === "IMG" && current.closest("[class*='message'], [class*='bubble']") !== null) return current;
      current = current.parentElement;
    }
    return null;
  }

  document.addEventListener("pointerdown", function (event) {
    var card = attachCard(event.target);
    if (card === null) return;
    if (attachTimer !== 0) window.clearTimeout(attachTimer);
    attachTimer = window.setTimeout(function () {
      attachTimer = 0;
      attachSuppress = true;
      try { if (navigator.vibrate) navigator.vibrate(12); } catch (error) { /* opcional */ }
      longPressDownload(card);
    }, 480);
  }, true);

  ["pointerup", "pointerleave", "pointercancel"].forEach(function (name) {
    document.addEventListener(name, function () {
      if (attachTimer !== 0) { window.clearTimeout(attachTimer); attachTimer = 0; }
    }, true);
  });

  document.addEventListener("click", function (event) {
    if (attachSuppress) { attachSuppress = false; event.stopPropagation(); event.preventDefault(); }
  }, true);

  // Sonda temporária: registra a estrutura do elemento que recebeu o toque longo.
  var probeTimer = 0;
  document.addEventListener("pointerdown", function (event) {
    var target = event.target;
    if (target === null || typeof target.closest !== "function") return;
    if (target.closest("#sig-voice-button") !== null) return;
    if (probeTimer !== 0) window.clearTimeout(probeTimer);
    probeTimer = window.setTimeout(function () {
      probeTimer = 0;
      var chain = [];
      var node = target;
      for (var depth = 0; depth < 6 && node !== null; depth++) {
        chain.push(String(node.tagName) + "." + String(node.className || "").slice(0, 70));
        node = node.parentElement;
      }
      var hasAnchor = target.closest("a[download]") !== null;
      try {
        fetch("/api/sig/diag", {
          method: "POST", headers: { "content-type": "application/json" },
          body: JSON.stringify({
            version: "1.15.0", event: "probe-longpress",
            chain: chain, anchor: hasAnchor,
            text: String(target.textContent || "").slice(0, 50)
          })
        }).catch(function () {});
      } catch (error) { /* opcional */ }
    }, 480);
  }, true);
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      var href = this.href || '';
      if (href.indexOf('blob:') === 0 && this.hasAttribute('download') && blobUrls.has(href)) {
        var blob = blobUrls.get(href);
        saveBlob(blob, this.getAttribute('download') || 'download');
        return;
      }
    } catch (error) { /* fall through to the original click */ }
    return click.apply(this, arguments);
  };
})();
