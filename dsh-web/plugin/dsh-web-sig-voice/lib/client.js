window.__ModuleLoader__.load({
  id: "dsh-web-sig-voice",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    let react = require("react");
    let jsx = require("react/jsx-runtime");

    const COLORS = {
      title: "var(--dsw-alias-label-primary, #f2f2f2)",
      body: "var(--dsw-alias-label-secondary, #b9bcc4)",
      button: "var(--dsw-alias-button-primary-fill, #4d6bfe)",
      buttonText: "var(--dsw-alias-label-inverted, #fff)"
    };

    function VoiceSection() {
      return jsx.jsxs("div", {
        style: { display: "flex", flexDirection: "column", gap: "14px", padding: "4px 0", maxWidth: "640px" },
        children: [
          jsx.jsx("h2", { style: { margin: 0, fontSize: "18px", lineHeight: "26px", color: COLORS.title } }, "Transcrição por voz"),
          jsx.jsx("p", {
            style: { margin: 0, fontSize: "13px", lineHeight: "21px", color: COLORS.body },
            children: "Provedor e chaves usados pelo microfone do SIG Harness no composer. O áudio sai do app direto para o provedor escolhido; não passa pelo Termux nem pelo bridge."
          }),
          jsx.jsx("p", {
            style: { margin: 0, fontSize: "13px", lineHeight: "21px", color: COLORS.body },
            children: "Opções: Meta Muse Voice e Gemini 3.5 Transcribe. Se a chave estiver vazia, o microfone abre este ajuste automaticamente."
          }),
          jsx.jsx("button", {
            type: "button",
            onClick: function () {
              if (window.SigVoice && typeof window.SigVoice.openSettings === "function") window.SigVoice.openSettings();
            },
            style: {
              alignSelf: "flex-start", border: 0, borderRadius: "10px", padding: "10px 16px",
              background: COLORS.button, color: COLORS.buttonText, fontSize: "14px", fontWeight: "500", cursor: "pointer"
            }
          }, "Configurar transcrição")
        ]
      });
    }

    const inject = ["slots"];

    function apply(ctx) {
      ctx.slots.inject("settings.section", () => ctx.slots.register({
        name: "settings.section",
        id: "sig-voice",
        order: 90,
        label: () => "Voz"
      }, VoiceSection));
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }
});
