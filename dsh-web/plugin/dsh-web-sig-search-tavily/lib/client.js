window.__ModuleLoader__.load({
  id: "dsh-web-sig-search-tavily",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    let react = require("react");
    let jsx = require("react/jsx-runtime");

    var TEXT = "var(--dsw-alias-label-primary,#f2f2f2)";
    var MUTED = "var(--dsw-alias-label-secondary,#b9bcc4)";
    var FAINT = "var(--dsw-alias-label-tertiary,#8b8f98)";
    var LINE = "rgba(255,255,255,.12)";
    var ACCENT = "var(--dsw-alias-button-primary-fill,#4d6bfe)";
    var DANGER = "#ff9b9b";
    var OK = "#7ee2a8";
    var ENDPOINT = "/api/sig/tavily-key";

    var field = {
      width: "100%", boxSizing: "border-box", padding: "9px 11px", borderRadius: "9px",
      border: "1px solid " + LINE, background: "rgba(255,255,255,.04)", color: TEXT,
      fontSize: "13px", outline: "none"
    };
    var labelStyle = { fontSize: "11.5px", color: FAINT, marginBottom: "4px", display: "block" };
    var hintStyle = { fontSize: "12px", color: MUTED, lineHeight: "1.5", margin: "0" };

    function button(kind, disabled) {
      var filled = kind === "primary";
      return {
        border: filled ? "none" : "1px solid " + LINE,
        background: filled ? ACCENT : "transparent",
        color: filled ? "#fff" : TEXT,
        borderRadius: "9px", padding: "8px 14px", fontSize: "12.5px",
        cursor: disabled ? "default" : "pointer",
        opacity: disabled ? ".45" : "1"
      };
    }

    /* Fala com a rota do host; nunca recebe o valor da chave de volta. */
    function request(method, payload) {
      var init = { method: method, headers: { accept: "application/json" } };
      if (payload !== undefined) {
        init.headers["content-type"] = "application/json";
        init.body = JSON.stringify(payload);
      }
      return fetch(ENDPOINT, init).then(function (response) {
        return response.json().then(
          function (data) { return { ok: response.ok, data: data }; },
          function () { return { ok: false, data: {} }; }
        );
      });
    }

    function sourceLabel(effective) {
      if (effective === "cli-config") return "arquivo do tvly (~/.tavily/config.json)";
      if (effective === "environment") return "ambiente do processo";
      return "cofre de credenciais do DSH";
    }

    function StatusLine(props) {
      var state = props.state;
      if (state === null) {
        return jsx.jsx("p", { style: hintStyle, children: "Verificando a chave..." });
      }
      if (state.error !== undefined) {
        return jsx.jsx("p", { style: { fontSize: "12.5px", color: DANGER, margin: "0" }, children: state.error });
      }
      // Host antigo não manda `effective`; cai para o que o cofre sabe.
      var effective = state.effective === undefined
        ? (state.configured === true ? "stored" : null)
        : state.effective;
      if (effective) {
        return jsx.jsxs("div", {
          children: [
            jsx.jsx("p", {
              style: { fontSize: "13px", color: OK, margin: "0 0 2px" },
              children: "Chave em uso"
            }),
            jsx.jsx("p", {
              style: hintStyle,
              children: "Origem: " + sourceLabel(effective) + ". As buscas do harness vão para o Tavily."
            }),
            effective === "cli-config"
              ? jsx.jsx("p", {
                  style: { fontSize: "11.5px", color: FAINT, margin: "4px 0 0" },
                  children: "Salvar aqui move a chave para o cofre do DSH; remover só apaga o que está no cofre."
                })
              : null
          ]
        });
      }
      return jsx.jsxs("div", {
        children: [
          jsx.jsx("p", {
            style: { fontSize: "13px", color: DANGER, margin: "0 0 2px" },
            children: "Nenhuma chave configurada"
          }),
          jsx.jsx("p", {
            style: hintStyle,
            children: "Sem chave, toda busca na web falha. Cole a chave abaixo e salve."
          })
        ]
      });
    }

    function SearchSection() {
      var [state, setState] = react.useState(null);
      var [draft, setDraft] = react.useState("");
      var [busy, setBusy] = react.useState(false);
      var [notice, setNotice] = react.useState(null);

      function refresh() {
        request("GET").then(function (out) {
          if (out.ok) setState(out.data);
          else setState({ error: out.data && out.data.error ? out.data.error : "Falha ao consultar o host." });
        }, function () {
          setState({ error: "Não foi possível falar com o host." });
        });
      }

      react.useEffect(function () { refresh(); }, []);

      function send(payload, successText) {
        setBusy(true);
        setNotice(null);
        request("POST", payload).then(
          function (out) {
            if (!out.ok) {
              setNotice({
                kind: "error",
                text: out.data && out.data.error ? out.data.error : "Não foi possível salvar."
              });
              return;
            }
            setState(out.data);
            setDraft("");
            setNotice({ kind: "ok", text: successText });
          },
          function () {
            setNotice({ kind: "error", text: "Não foi possível falar com o host." });
          }
        ).finally(function () { setBusy(false); });
      }

      var writable = state !== null && state.error === undefined && state.writable !== false;
      var configured = state !== null && state.error === undefined && state.configured === true;
      var canSave = writable && !busy && draft.trim().length > 0;

      return jsx.jsxs("div", {
        style: { padding: "4px 0", display: "flex", flexDirection: "column", gap: "16px", maxWidth: "560px" },
        children: [
          jsx.jsxs("div", {
            style: { border: "1px solid " + LINE, borderRadius: "12px", padding: "16px", display: "flex", flexDirection: "column", gap: "12px" },
            children: [
              jsx.jsx(StatusLine, { state: state }),
              jsx.jsxs("div", {
                children: [
                  jsx.jsx("label", { style: labelStyle, htmlFor: "sig-tavily-key", children: "Chave da API Tavily" }),
                  jsx.jsx("input", {
                    id: "sig-tavily-key",
                    type: "password",
                    autoComplete: "off",
                    spellCheck: false,
                    placeholder: "tvly-...",
                    value: draft,
                    disabled: !writable || busy,
                    onChange: function (event) { setDraft(event.target.value); },
                    onKeyDown: function (event) {
                      if (event.key === "Enter" && canSave) { send({ apiKey: draft }, "Chave salva."); }
                    },
                    style: field
                  }),
                  jsx.jsx("p", {
                    style: { fontSize: "11.5px", color: FAINT, margin: "6px 0 0" },
                    children: writable
                      ? "Fica no cofre de credenciais do DSH — nunca no repositório nem na configuração."
                      : "Esta chave vem do ambiente do processo; não pode ser alterada por aqui."
                  })
                ]
              }),
              jsx.jsxs("div", {
                style: { display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" },
                children: [
                  jsx.jsx("button", {
                    type: "button",
                    disabled: !canSave,
                    onClick: function () { send({ apiKey: draft }, "Chave salva."); },
                    style: button("primary", !canSave),
                    children: busy ? "Salvando..." : "Salvar"
                  }),
                  configured && writable
                    ? jsx.jsx("button", {
                        type: "button",
                        disabled: busy,
                        onClick: function () { send({ action: "remove" }, "Chave removida."); },
                        style: button("plain", busy),
                        children: "Remover"
                      })
                    : null,
                  notice !== null
                    ? jsx.jsx("span", {
                        style: {
                          fontSize: "12px",
                          color: notice.kind === "error" ? DANGER : OK
                        },
                        children: notice.text
                      })
                    : null
                ]
              })
            ]
          }),
          jsx.jsx("p", {
            style: { fontSize: "11.5px", color: FAINT, margin: "0" },
            children: "Depois de salvar, a próxima busca já usa a chave nova — sem reiniciar o harness."
          })
        ]
      });
    }

    const inject = ["slots"];

    function apply(ctx) {
      ctx.slots.inject("settings.section", () => ctx.slots.register({
        name: "settings.section",
        id: "sig-web-search",
        order: 45,
        label: "Busca na web"
      }, SearchSection));
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }
});
