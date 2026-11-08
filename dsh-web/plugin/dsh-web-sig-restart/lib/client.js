window.__ModuleLoader__.load({
  id: "dsh-web-sig-restart",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var ICON = "M4 4v6h6M20 20v-6h-6M19.5 9a7.5 7.5 0 0 0-13-3.5L4 8M4.5 15a7.5 7.5 0 0 0 13 3.5L20 16";
    var RED = "#ff6b6b";
    var button = null;
    var dialog = null;
    var keepTimer = null;
    var closeTimer = null;

    /** Envia estado/diagnóstico do rodapé para o host. */
    function post(payload) {
      try {
        fetch("/api/sig/diag", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(payload)
        }).catch(function () {});
      } catch (error) { /* opcional */ }
    }

    function sendDiagnostics() {
      try {
        var nodes = document.querySelectorAll('[class*="triggerRow"], [class*="footArea"], [data-mobile-nav]');
        var rows = [];
        for (var i = 0; i < nodes.length && i < 12; i += 1) {
          rows.push({
            cls: String(nodes[i].className || ""),
            nav: nodes[i].getAttribute ? nodes[i].getAttribute("data-mobile-nav") : null,
            html: (nodes[i].outerHTML || "").slice(0, 240)
          });
        }
        post({ version: "1.9.0", href: String(location.href).slice(0, 120), nodes: rows });
      } catch (error) { /* opcional */ }
    }

    /**
     * Ajustes visuais do rodapé:
     * - engrenagem injetada pelo dsh-web-mobile (sem ação desde que a conta saiu) é ocultada;
     * - o rótulo "Settings" sai, restando só o ícone.
     */
    function installFooterStyle() {
      if (document.getElementById("sig-footer-style") !== null) return;
      var style = document.createElement("style");
      style.id = "sig-footer-style";
      style.textContent = [
        '[data-mobile-nav="quick-settings"], [data-mobile-nav="quick-settings-wrap"]{display:none !important}',
        '[class*="triggerRow"] [class*="triggerLabel"]{display:none !important}',
        '[class*="triggerRow"] [class*="trigger"]{gap:0 !important}'
      ].join("\n");
      document.head.appendChild(style);
    }

    function restart() {
      closeConfirm();
      if (window.SigApp && typeof window.SigApp.restartHarness === "function") {
        window.SigApp.restartHarness();
        return;
      }
      window.location.reload();
    }

    function closeConfirm() {
      if (closeTimer !== null) { window.clearTimeout(closeTimer); closeTimer = null; }
      if (dialog !== null && dialog.parentNode !== null) dialog.parentNode.removeChild(dialog);
      dialog = null;
    }

    function makeChoice(label, primary, onPick) {
      var choice = document.createElement("button");
      choice.type = "button";
      choice.textContent = label;
      choice.style.cssText = "flex:1;min-height:34px;border-radius:8px;font-size:13px;font-weight:500;"
        + "cursor:pointer;border:1px solid " + (primary ? "transparent" : "rgba(255,255,255,.16)") + ";"
        + "background:" + (primary ? RED : "transparent") + ";"
        + "color:" + (primary ? "#1b1c1f" : "inherit");
      choice.addEventListener("pointerdown", function (event) {
        event.preventDefault();
        event.stopPropagation();
        onPick();
      });
      choice.addEventListener("click", function (event) {
        event.preventDefault();
        event.stopPropagation();
        onPick();
      });
      return choice;
    }

    /** Pergunta de confirmação: cartão acima do botão, com Sim/Não. */
    function openConfirm() {
      if (dialog !== null) return;
      var card = document.createElement("div");
      card.setAttribute("data-sig-confirm", "1");
      card.style.cssText = "position:fixed;z-index:2147483000;width:212px;box-sizing:border-box;"
        + "padding:12px;border-radius:12px;background:#26282d;border:1px solid rgba(255,255,255,.14);"
        + "box-shadow:0 10px 30px rgba(0,0,0,.45);color:#e6e7ea;"
        + "font:13px/1.35 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif";

      var title = document.createElement("div");
      title.textContent = "Reiniciar o harness?";
      title.style.cssText = "font-weight:600;margin-bottom:2px";

      var hint = document.createElement("div");
      hint.textContent = "O dsh web encerra e sobe de novo.";
      hint.style.cssText = "color:#9a9da6;font-size:11.5px;margin-bottom:10px";

      var row = document.createElement("div");
      row.style.cssText = "display:flex;gap:8px";
      row.appendChild(makeChoice("Não", false, closeConfirm));
      row.appendChild(makeChoice("Sim", true, restart));

      card.appendChild(title);
      card.appendChild(hint);
      card.appendChild(row);
      document.body.appendChild(card);
      dialog = card;

      var box = button !== null && button.getBoundingClientRect ? button.getBoundingClientRect() : null;
      var width = 212;
      var left = box === null ? window.innerWidth - width - 16 : Math.min(box.right - width, window.innerWidth - width - 12);
      card.style.left = Math.max(12, left) + "px";
      card.style.top = "auto";
      card.style.bottom = box === null
        ? "84px"
        : Math.max(12, window.innerHeight - box.top + 8) + "px";

      closeTimer = window.setTimeout(closeConfirm, 12000);
      post({ version: "1.9.0", event: "confirm-open", armed: true });
    }

    /** Toque no botão: abre a pergunta em vez de reiniciar direto. */
    function onPointerDown(event) {
      var target = event.target;
      if (target === null || typeof target.closest !== "function") return;
      if (target.closest("[data-sig-restart]") === null) {
        if (dialog !== null && target.closest("[data-sig-confirm]") === null) closeConfirm();
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      openConfirm();
    }

    function onDocumentClick(event) {
      var target = event.target;
      if (target === null || typeof target.closest !== "function") return;
      if (target.closest("[data-sig-restart]") === null) return;
      event.preventDefault();
      event.stopPropagation();
      openConfirm();
    }

    /** Mantém o botão como último filho da linha da engrenagem, empurrado à direita. */
    function place() {
      var row = document.querySelector('[class*="triggerRow"]');
      if (row === null) {
        if (button !== null) button.style.display = "none";
        return;
      }
      if (button === null) {
        button = document.createElement("button");
        button.type = "button";
        button.setAttribute("data-sig-restart", "1");
        button.style.cssText = "width:28px;height:28px;border-radius:999px;border:none;background:transparent;"
          + "display:grid;place-items:center;padding:0;flex:none;margin-left:auto;cursor:pointer;color:" + RED;
        button.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
          + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + ICON + '"/></svg>';
        button.title = "Reiniciar harness";
        button.setAttribute("aria-label", "Reiniciar harness");
      }
      button.style.display = "grid";
      if (button.parentElement !== row) row.appendChild(button);
    }

    function apply() {
      installFooterStyle();
      document.addEventListener("pointerdown", onPointerDown, true);
      document.addEventListener("click", onDocumentClick, true);
      place();
      if (keepTimer === null) keepTimer = window.setInterval(place, 900);
      window.setTimeout(sendDiagnostics, 2500);
    }

    exports.apply = apply;
    exports.inject = [];
    return module.exports;
  }
});
