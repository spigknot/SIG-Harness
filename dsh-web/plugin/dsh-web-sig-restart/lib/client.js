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

      var look = {
        display: "flex", alignItems: "center", gap: "10px", width: "100%",
        minHeight: "32px", padding: "6px 10px", border: 0, borderRadius: "8px",
        background: "transparent", color: "var(--dsw-alias-label-secondary, #b9bcc4)",
        fontSize: "13px", lineHeight: "20px", textAlign: "left", flex: "none",
        cursor: busy ? "default" : "pointer", opacity: busy ? "0.55" : "1"
      };

      return jsx.jsxs("button", {
        type: "button",
        onClick: activate,
        onMouseEnter: function (event) { if (!busy) event.currentTarget.style.background = "var(--dsw-alias-interactive-bg-hover, rgba(255,255,255,.06))"; },
        onMouseLeave: function (event) { event.currentTarget.style.background = "transparent"; },
        title: busy ? "Reiniciando o harness…" : "Reiniciar harness (encerra o dsh web e sobe de novo)",
        "aria-label": "Reiniciar harness",
        style: look,
        children: [
          jsx.jsx("svg", {
            width: "15", height: "15", viewBox: "0 0 24 24", fill: "none",
            stroke: "currentColor", "stroke-width": "1.8", "stroke-linecap": "round",
            "stroke-linejoin": "round", "aria-hidden": "true", style: { flex: "none" },
            children: jsx.jsx("path", { d: ICON })
          }),
          jsx.jsx("span", { children: busy ? "Reiniciando…" : "Reiniciar harness" })
        ]
      });
    }
    const inject = ["slots"];

    function apply(ctx) {
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
