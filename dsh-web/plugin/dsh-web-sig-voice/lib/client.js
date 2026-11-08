window.__ModuleLoader__.load({
  id: "dsh-web-sig-voice",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    let react = require("react");
    let jsx = require("react/jsx-runtime");

    var PROVIDERS = [
      { id: "muse", label: "Meta Muse Voice", key: "muse" },
      { id: "gemini", label: "Gemini 3.5 Transcribe", key: "gemini" }
    ];

    var TEXT = "var(--dsw-alias-label-primary,#f2f2f2)";
    var MUTED = "var(--dsw-alias-label-secondary,#b9bcc4)";
    var FAINT = "var(--dsw-alias-label-tertiary,#8b8f98)";
    var LINE = "rgba(255,255,255,.12)";
    var ACCENT = "var(--dsw-alias-button-primary-fill,#4d6bfe)";

    function bridge() {
      var api = window.SigVoice;
      return api && typeof api.keysState === "function" ? api : null;
    }

    function readState() {
      var api = bridge();
      if (api === null) return { muse: false, gemini: false, provider: "gemini" };
      try {
        var raw = api.keysState();
        var parsed = JSON.parse(raw);
        return {
          muse: parsed.muse === true,
          gemini: parsed.gemini === true,
          provider: String(parsed.provider || "gemini")
        };
      } catch (error) {
        return { muse: false, gemini: false, provider: "gemini" };
      }
    }

    function KeyDialog(props) {
      var [muse, setMuse] = react.useState("");
      var [gemini, setGemini] = react.useState("");
      var field = {
        width: "100%", boxSizing: "border-box", padding: "9px 11px", borderRadius: "9px",
        border: "1px solid " + LINE, background: "rgba(255,255,255,.04)", color: TEXT,
        fontSize: "13px", outline: "none"
      };
      var labelStyle = { fontSize: "11.5px", color: FAINT, marginBottom: "4px", display: "block" };
      function save() {
        var api = bridge();
        if (api !== null) {
          try { api.setKeys(muse, gemini); } catch (error) { /* bridge ausente */ }
        }
        props.onClose();
      }
      return jsx.jsx("div", {
        style: {
          position: "fixed", inset: 0, zIndex: 2147483000, background: "rgba(0,0,0,.55)",
          display: "grid", placeItems: "center", padding: "18px"
        },
        onClick: function (event) { if (event.target === event.currentTarget) props.onClose(); },
        children: jsx.jsxs("div", {
          style: {
            width: "min(420px,100%)", padding: "18px", borderRadius: "14px", background: "#26282d",
            border: "1px solid " + LINE, boxShadow: "0 18px 50px rgba(0,0,0,.5)", color: TEXT,
            font: "13px/1.4 system-ui,-apple-system,Roboto,sans-serif", display: "flex",
            flexDirection: "column", gap: "12px"
          },
          children: [
            jsx.jsx("div", { style: { fontSize: "14px", fontWeight: "600" }, children: "Chaves de API" }),
            jsx.jsx("div", {
              style: { fontSize: "12px", color: MUTED },
              children: "As chaves ficam no aparelho. Deixe em branco para manter a atual."
            }),
            jsx.jsxs("div", {
              children: [
                jsx.jsx("label", { style: labelStyle, children: "Meta Muse" }),
                jsx.jsx("input", {
                  type: "password", value: muse, placeholder: props.state.muse ? "configurada" : "não configurada",
                  onChange: function (event) { setMuse(event.target.value); }, style: field
                })
              ]
            }),
            jsx.jsxs("div", {
              children: [
                jsx.jsx("label", { style: labelStyle, children: "Google AI Studio (Gemini)" }),
                jsx.jsx("input", {
                  type: "password", value: gemini, placeholder: props.state.gemini ? "configurada" : "não configurada",
                  onChange: function (event) { setGemini(event.target.value); }, style: field
                })
              ]
            }),
            jsx.jsxs("div", {
              style: { display: "flex", gap: "8px", justifyContent: "flex-end", marginTop: "2px" },
              children: [
                jsx.jsx("button", {
                  type: "button", onClick: props.onClose,
                  style: {
                    border: "1px solid " + LINE, background: "transparent", color: TEXT,
                    borderRadius: "9px", padding: "8px 14px", fontSize: "13px", cursor: "pointer"
                  }, children: "Cancelar"
                }),
                jsx.jsx("button", {
                  type: "button", onClick: save,
                  style: {
                    border: 0, background: ACCENT, color: "#fff", borderRadius: "9px",
                    padding: "8px 16px", fontSize: "13px", fontWeight: "500", cursor: "pointer"
                  }, children: "Salvar"
                })
              ]
            })
          ]
        })
      });
    }

    /** Seção Voz, dentro da aba de modelos. */
    function VoiceBlock() {
      var [state, setState] = react.useState(readState());
      var [dialog, setDialog] = react.useState(false);

      function refresh() { setState(readState()); }

      function pick(provider) {
        if (!state[provider.key]) {
          setDialog(true);
          return;
        }
        var api = bridge();
        if (api !== null) { try { api.setSttProvider(provider.id); } catch (error) { /* opcional */ } }
        refresh();
      }

      var rows = PROVIDERS.map(function (provider) {
        var available = state[provider.key] === true;
        var active = state.provider === provider.id;
        return jsx.jsxs("button", {
          key: provider.id,
          type: "button",
          onClick: function () { pick(provider); },
          style: {
            display: "flex", alignItems: "center", gap: "10px", width: "100%", textAlign: "left",
            padding: "10px 12px", borderRadius: "10px", cursor: "pointer",
            border: "1px solid " + (active ? ACCENT : LINE),
            background: active ? "rgba(77,107,254,.10)" : "transparent",
            color: TEXT, fontSize: "13px", opacity: available ? "1" : "0.5"
          },
          children: [
            jsx.jsx("span", {
              style: {
                width: "14px", height: "14px", borderRadius: "50%", flex: "none",
                border: "2px solid " + (active ? ACCENT : FAINT),
                background: active ? ACCENT : "transparent"
              }
            }),
            jsx.jsx("span", { style: { flex: "1 1 auto" }, children: provider.label }),
            jsx.jsx("span", {
              style: { fontSize: "11.5px", color: available ? FAINT : "#e0a44a" },
              children: available ? "" : "requer chave"
            })
          ]
        });
      });

      return jsx.jsxs("section", {
        style: { display: "flex", flexDirection: "column", gap: "10px", paddingTop: "18px", marginTop: "6px", borderTop: "1px solid " + LINE },
        children: [
          jsx.jsxs("div", {
            children: [
              jsx.jsx("div", { style: { fontSize: "14px", fontWeight: "600", color: TEXT }, children: "Voz" }),
              jsx.jsx("div", {
                style: { fontSize: "12px", color: MUTED, marginTop: "2px" },
                children: "Provedor da transcrição do microfone. O áudio sai do app direto para o provedor; não passa pelo Termux."
              })
            ]
          }),
          jsx.jsxs("div", {
            style: { display: "flex", flexDirection: "column", gap: "6px" },
            children: rows
          }),
          jsx.jsx("div", {
            children: jsx.jsx("button", {
              type: "button",
              onClick: function () { setDialog(true); },
              style: {
                alignSelf: "flex-start", border: "1px solid " + LINE, background: "transparent",
                color: TEXT, borderRadius: "9px", padding: "8px 14px", fontSize: "12.5px", cursor: "pointer"
              },
              children: "Chaves API"
            })
          }),
          dialog ? jsx.jsx(KeyDialog, { state: state, onClose: function () { setDialog(false); refresh(); } }) : null
        ]
      });
    }

    const inject = ["slots"];

    function apply(ctx) {
      ctx.slots.inject("settings.models.footer", () => ctx.slots.register({
        name: "settings.models.footer",
        id: "sig-voice",
        order: 90
      }, VoiceBlock));
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }
});
