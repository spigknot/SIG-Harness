window.__ModuleLoader__.load({
  id: "dsh-web-sig-restart",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var ICON = "M4 4v6h6M20 20v-6h-6M19.5 9a7.5 7.5 0 0 0-13-3.5L4 8M4.5 15a7.5 7.5 0 0 0 13 3.5L20 16";
    var RED = "#ff6b6b";
    var IDLE = "var(--dsw-alias-label-tertiary, #8b8f98)";
    var button = null;
    var armed = false;
    var armTimer = null;
    var keepTimer = null;

    /** Envia o estado real do rodapé para o host, para conferir a estrutura. */
    function sendDiagnostics() {
      try {
        var nodes = document.querySelectorAll('[class*="triggerRow"], [class*="footArea"], [data-mobile-nav]');
        var rows = [];
        for (var i = 0; i < nodes.length && i < 12; i += 1) {
          rows.push({
            cls: String(nodes[i].className || ""),
            nav: nodes[i].getAttribute ? nodes[i].getAttribute("data-mobile-nav") : null,
            html: (nodes[i].outerHTML || "").slice(0, 320)
          });
        }
        fetch("/api/sig/diag", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ version: "1.5.0", href: String(location.href).slice(0, 120), nodes: rows })
        }).catch(function () {});
      } catch (error) { /* opcional */ }
    }

    function paint() {
      if (button === null) return;
      button.style.color = armed ? "#fff" : RED;
      button.style.background = armed ? RED : "transparent";
      button.title = armed ? "Clique de novo para confirmar o reinício" : "Reiniciar harness";
      button.setAttribute("aria-label", armed ? "Confirmar reinício do harness" : "Reiniciar harness");
    }

    function disarm() {
      armed = false;
      if (armTimer !== null) { window.clearTimeout(armTimer); armTimer = null; }
      paint();
    }

    function activate(event) {
      if (event) { event.preventDefault(); event.stopPropagation(); }
      if (!armed) {
        armed = true;
        paint();
        armTimer = window.setTimeout(disarm, 4000);
        return;
      }
      disarm();
      if (window.SigApp && typeof window.SigApp.restartHarness === "function") {
        window.SigApp.restartHarness();
        return;
      }
      window.location.reload();
    }

    /** O botão entra como último filho da linha da engrenagem e é empurrado para a direita. */
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
          + "display:grid;place-items:center;padding:0;flex:none;margin-left:auto;cursor:pointer;"
          + "color:" + RED + ";transition:background-color .12s,color .12s";
        button.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
          + 'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + ICON + '"/></svg>';
        button.addEventListener("click", activate);
        paint();
      }
      button.style.display = "grid";
      if (button.parentElement !== row) row.appendChild(button);
      else row.appendChild(button); // mantém por último
    }

    function startKeepAlive() {
      if (keepTimer !== null) return;
      keepTimer = window.setInterval(place, 900);
    }

    /**
     * Ajustes visuais do rodapé:
     * - a engrenagem injetada pelo dsh-web-mobile (data-mobile-nav=quick-settings) ficou sem
     *   ação quando desativamos a linha de conta — é ocultada;
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

    function apply() {
      installFooterStyle();
      place();
      startKeepAlive();
      window.setTimeout(sendDiagnostics, 2500);
    }

    exports.apply = apply;
    exports.inject = [];
    return module.exports;
  }
});
