window.__ModuleLoader__.load({
  id: "dsh-web-sig-restart",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    let react = require("react");
    let jsx = require("react/jsx-runtime");

    var ICON = "M4 4v6h6M20 20v-6h-6M19.5 9a7.5 7.5 0 0 0-13-3.5L4 8M4.5 15a7.5 7.5 0 0 0 13 3.5L20 16";

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
        // Sem o gancho nativo (navegador puro), recarregar a página é o mais próximo.
        window.location.reload();
      }

      return jsx.jsxs("button", {
        type: "button",
        onClick: activate,
        title: "Reiniciar o harness: encerra o dsh web e sobe um processo novo",
        "aria-label": "Reiniciar o harness",
        style: {
          display: "flex", alignItems: "center", gap: "8px", width: "100%",
          border: 0, background: "transparent", color: "var(--dsw-alias-label-secondary, #b9bcc4)",
          fontSize: "13px", padding: "6px 10px", borderRadius: "8px", cursor: "pointer",
          textAlign: "left", opacity: busy ? "0.6" : "1"
        },
        children: [
          jsx.jsx("svg", {
            width: "15", height: "15", viewBox: "0 0 24 24", fill: "none",
            stroke: "currentColor", "stroke-width": "1.8", "stroke-linecap": "round",
            "stroke-linejoin": "round", "aria-hidden": "true",
            children: jsx.jsx("path", { d: ICON })
          }),
          jsx.jsx("span", { children: busy ? "Reiniciando…" : "Reiniciar harness" })
        ]
      });
    }

    const inject = ["slots"];

    function apply(ctx) {
      ctx.slots.inject("settings.launcher", function () {
        return ctx.slots.register({
          name: "settings.launcher",
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
