window.__ModuleLoader__.load({
  id: "dsh-web-sig-restart",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    let react = require("react");
    let jsx = require("react/jsx-runtime");

    var ICON = "M4 4v6h6M20 20v-6h-6M19.5 9a7.5 7.5 0 0 0-13-3.5L4 8M4.5 15a7.5 7.5 0 0 0 13 3.5L20 16";

    /** Botão compacto do rodapé: convive com a engrenagem, não ocupa o lugar dela. */
    function RestartButton() {
      const [busy, setBusy] = react.useState(false);

      function activate(event) {
        if (event) { event.preventDefault(); event.stopPropagation(); }
        if (busy) return;
        if (window.SigApp && typeof window.SigApp.restartHarness === "function") {
          setBusy(true);
          window.SigApp.restartHarness();
          return;
        }
        window.location.reload();
      }

      return jsx.jsx("button", {
        type: "button",
        onClick: activate,
        onMouseEnter: function (event) { if (!busy) event.currentTarget.style.background = "var(--dsw-alias-interactive-bg-hover, rgba(255,255,255,.06))"; },
        onMouseLeave: function (event) { event.currentTarget.style.background = "transparent"; },
        title: busy ? "Reiniciando o harness…" : "Reiniciar harness (encerra o dsh web e sobe de novo)",
        "aria-label": "Reiniciar harness",
        style: {
          width: "28px", height: "28px", borderRadius: "999px", border: "none",
          background: "transparent", color: "var(--dsw-alias-label-tertiary, #8b8f98)",
          display: "grid", placeItems: "center", padding: 0, flex: "none",
          cursor: busy ? "default" : "pointer", opacity: busy ? "0.5" : "1"
        },
        children: jsx.jsx("svg", {
          width: "15", height: "15", viewBox: "0 0 24 24", fill: "none",
          stroke: "currentColor", "stroke-width": "1.8", "stroke-linecap": "round",
          "stroke-linejoin": "round", "aria-hidden": "true",
          children: jsx.jsx("path", { d: ICON })
        })
      });
    }

    /**
     * Ajustes do rodapé da barra lateral:
     * - a engrenagem da direita vem do dsh-web-mobile (data-mobile-nav=quick-settings) e ficou
     *   presa à linha de conta, que desativamos — sem ação, só ocupa espaço;
     * - o rótulo "Settings" sai e o item fica só com o ícone.
     */
    function installFooterStyle() {
      if (document.getElementById("sig-footer-style") !== null) return;
      var style = document.createElement("style");
      style.id = "sig-footer-style";
      style.textContent = [
        '[data-mobile-nav="quick-settings"], [data-mobile-nav="quick-settings-wrap"]{display:none !important}',
        '[class*="triggerRow"] [class*="triggerLabel"]{display:none !important}',
        '[class*="triggerRow"] [class*="trigger"]{gap:0 !important}',
        /* coloca ações e configurações na MESMA linha, com o reiniciar à direita */
        '[class*="footArea"]{display:flex !important;align-items:center !important;gap:4px}',
        '[class*="settingsArea"]{order:1;flex:1 1 auto;min-width:0}',
        '[class*="footerActions"]{order:2;flex:none;margin-left:auto}'
      ].join("\n");;
      document.head.appendChild(style);
    }

    /** Envia o estado real do rodapé para o host, para conferir os seletores. */
    function sendDiagnostics() {
      try {
        var rows = [];
        var nodes = document.querySelectorAll("[class*=\"footerActions\"], [class*=\"footArea\"], [class*=\"triggerRow\"], [data-mobile-nav]");
        for (var i = 0; i < nodes.length && i < 12; i += 1) {
          var el = nodes[i];
          rows.push({
            cls: String(el.className || ""),
            nav: el.getAttribute ? el.getAttribute("data-mobile-nav") : null,
            html: (el.outerHTML || "").slice(0, 320)
          });
        }
        var style = document.getElementById("sig-footer-style");
        var payload = JSON.stringify({
          href: String(window.location.href).slice(0, 120),
          styleInjected: style !== null,
          styleText: style === null ? "" : String(style.textContent).slice(0, 400),
          nodes: rows
        });
        fetch("/api/sig/diag", { method: "POST", headers: { "content-type": "application/json" }, body: payload }).catch(function () {});
      } catch (error) { /* diagnóstico é opcional */ }
    }

    const inject = ["slots"];

    function apply(ctx) {
      installFooterStyle();
      window.setTimeout(sendDiagnostics, 2500);
      ctx.slots.inject("sidebar.footer.action", function () {
        return ctx.slots.register({
          name: "sidebar.footer.action",
          id: "sig-restart",
          order: 50,
          label: function () { return "Reiniciar harness"; }
        }, RestartButton);
      });
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }
});
