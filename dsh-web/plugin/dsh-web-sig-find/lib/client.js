window.__ModuleLoader__.load({
  id: "dsh-web-sig-find",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
    let react = require("react");
    let jsx = require("react/jsx-runtime");

    const MARK_ATTR = "data-sig-find";
    const UI_ATTR = "data-sig-find-ui";
    const HIT = "sig-find-hit";
    const ACTIVE = "sig-find-active";
    const EVENT = "sig-find-run";

    const COLORS = {
      text: "var(--dsw-alias-label-primary, #f2f2f2)",
      faint: "var(--dsw-alias-label-tertiary, #8b8f98)",
      bg: "var(--dsw-alias-bg-elevated, #1b1c1f)",
      border: "var(--dsw-alias-border-l3, #2c2e33)"
    };

    /** The conversation pane: nearest ancestor of the View tabs that looks like a module root. */
    function findScope() {
      let el = document.querySelector("[data-conversation-tabs]");
      while (el !== null && el !== document.body) {
        const cls = typeof el.className === "string" ? el.className : "";
        if (cls.indexOf("_root") !== -1) return el;
        el = el.parentElement;
      }
      return document.body;
    }

    function clearMarks(scope) {
      const marks = scope.querySelectorAll("mark[" + MARK_ATTR + "]");
      for (let i = 0; i < marks.length; i += 1) {
        const mark = marks[i];
        const parent = mark.parentNode;
        if (parent === null) continue;
        while (mark.firstChild !== null) parent.insertBefore(mark.firstChild, mark);
        parent.removeChild(mark);
        parent.normalize();
      }
    }

    function collectTextNodes(scope) {
      const out = [];
      const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          const value = node.nodeValue;
          if (value === null || value.trim() === "") return NodeFilter.FILTER_REJECT;
          const parent = node.parentElement;
          if (parent === null) return NodeFilter.FILTER_REJECT;
          const tag = parent.tagName;
          if (tag === "SCRIPT" || tag === "STYLE" || tag === "MARK" || tag === "TEXTAREA" || tag === "INPUT") return NodeFilter.FILTER_REJECT;
          if (parent.closest("[" + UI_ATTR + "]") !== null) return NodeFilter.FILTER_REJECT;
          if (parent.closest("[class*='_header']") !== null) return NodeFilter.FILTER_REJECT;
          return NodeFilter.FILTER_ACCEPT;
        }
      });
      let node = walker.nextNode();
      while (node !== null) {
        out.push(node);
        node = walker.nextNode();
      }
      return out;
    }

    function highlight(scope, needle) {
      const lower = needle.toLowerCase();
      const marks = [];
      const nodes = collectTextNodes(scope);
      for (let n = 0; n < nodes.length; n += 1) {
        const node = nodes[n];
        const text = node.nodeValue;
        if (text === null) continue;
        const hay = text.toLowerCase();
        let at = hay.indexOf(lower);
        if (at === -1) continue;
        const frag = document.createDocumentFragment();
        let cursor = 0;
        while (at !== -1) {
          if (at > cursor) frag.appendChild(document.createTextNode(text.slice(cursor, at)));
          const mark = document.createElement("mark");
          mark.setAttribute(MARK_ATTR, "1");
          mark.className = HIT;
          mark.textContent = text.slice(at, at + lower.length);
          frag.appendChild(mark);
          marks.push(mark);
          cursor = at + lower.length;
          at = hay.indexOf(lower, cursor);
        }
        if (cursor < text.length) frag.appendChild(document.createTextNode(text.slice(cursor)));
        node.parentNode.replaceChild(frag, node);
      }
      return marks;
    }

    function ensureStyle() {
      if (document.getElementById("sig-find-style") !== null) return;
      const style = document.createElement("style");
      style.id = "sig-find-style";
      style.textContent =
        "mark." + HIT + "{background:#f5c45133;color:inherit;border-radius:2px;padding:0 1px;outline:1px solid #f5c45155}" +
        "mark." + HIT + "." + ACTIVE + "{background:#f5c451aa;color:#111;outline:1px solid #f5c451}";
      document.head.appendChild(style);
    }

    /** Set by the mounted widget so the sidebar hook can drive it. */
    let controller = null;

    function installSidebarHook() {
      if (window.__sigFindHooked === true) return;
      window.__sigFindHooked = true;
      document.addEventListener("click", function (event) {
        const target = event.target;
        if (target === null || target instanceof Element !== true) return;
        const tree = target.closest("[class*='searchTree']");
        if (tree === null) return;
        const input = document.querySelector("[class*='searchInput']");
        const query = input !== null && typeof input.value === "string" ? input.value.trim() : "";
        if (query === "" || controller === null) return;
        // O resultado clicado abre a sessão; o widget reaplica a busca e salta para a última ocorrência.
        setTimeout(function () { controller.run(query, "last", 12); }, 250);
      }, true);
    }

    function FindBox() {
      const [query, setQuery] = react.useState("");
      const [total, setTotal] = react.useState(0);
      const [index, setIndex] = react.useState(-1);
      const marks = react.useRef([]);
      const retry = react.useRef(0);
      const timer = react.useRef(null);

      const focusMark = react.useCallback(function (next) {
        const list = marks.current;
        if (list.length === 0) return;
        const clamped = ((next % list.length) + list.length) % list.length;
        for (let i = 0; i < list.length; i += 1) list[i].classList.remove(ACTIVE);
        const mark = list[clamped];
        mark.classList.add(ACTIVE);
        mark.scrollIntoView({ block: "center", behavior: "smooth" });
        setIndex(clamped);
      }, []);

      const run = react.useCallback(function (value, jump, attempts) {
        ensureStyle();
        const scope = findScope();
        clearMarks(scope);
        if (value === "") {
          marks.current = [];
          setTotal(0);
          setIndex(-1);
          return;
        }
        const hits = highlight(scope, value);
        marks.current = hits;
        setTotal(hits.length);
        if (hits.length === 0) {
          setIndex(-1);
          if (attempts !== undefined && attempts > 0) {
            window.clearTimeout(timer.current);
            timer.current = window.setTimeout(function () { controller.run(value, jump, attempts - 1); }, 300);
          }
          return;
        }
        focusMark(jump === "last" ? hits.length - 1 : 0);
      }, [focusMark]);

      react.useEffect(function () {
        controller = { run: run };
        return function () { controller = null; if (timer.current !== null) window.clearTimeout(timer.current); };
      }, [run]);

      react.useEffect(function () {
        function onRun(event) {
          const detail = event.detail || {};
          if (typeof detail.query !== "string") return;
          setQuery(detail.query);
          run(detail.query, detail.jump === "last" ? "last" : "first", detail.jump === "last" ? 12 : 0);
        }
        window.addEventListener(EVENT, onRun);
        return function () { window.removeEventListener(EVENT, onRun); };
      }, [run]);

      function onSubmit(event) {
        event.preventDefault();
        run(query, "last", 0);
      }

      const counter = total === 0 ? (query === "" ? "" : "0") : (index + 1) + "/" + total;
      const buttonStyle = {
        border: "1px solid " + COLORS.border, background: "transparent", color: COLORS.text,
        borderRadius: "6px", width: "22px", height: "22px", lineHeight: "18px", fontSize: "11px",
        cursor: "pointer", padding: 0
      };

      return jsx.jsxs("form", {
        "data-sig-find-ui": "1",
        onSubmit: onSubmit,
        style: { display: "flex", alignItems: "center", gap: "6px", marginLeft: "auto", paddingBottom: "9px" },
        children: [
          jsx.jsx("input", {
            type: "text",
            value: query,
            placeholder: "Buscar nesta sessão",
            onChange: function (event) { setQuery(event.target.value); run(event.target.value, "first", 0); },
            onKeyDown: function (event) {
              if (event.key === "Enter") { event.preventDefault(); run(query, "last", 0); }
              if (event.key === "Escape") { setQuery(""); run("", "first", 0); }
            },
            style: {
              width: "150px", height: "22px", fontSize: "12px", color: COLORS.text,
              background: COLORS.bg, border: "1px solid " + COLORS.border, borderRadius: "6px", padding: "0 8px"
            }
          }),
          jsx.jsx("span", { style: { fontSize: "11px", color: COLORS.faint, minWidth: "34px", textAlign: "center" }, children: counter }),
          jsx.jsx("button", { type: "button", title: "Ocorrência anterior", style: buttonStyle, onClick: function () { focusMark(index - 1); }, children: "↑" }),
          jsx.jsx("button", { type: "button", title: "Próxima ocorrência", style: buttonStyle, onClick: function () { focusMark(index + 1); }, children: "↓" })
        ]
      });
    }

    const inject = ["slots"];

    function apply(ctx) {
      ensureStyle();
      installSidebarHook();
      ctx.slots.inject("conversation.session.header.views.trailing", function () {
        return ctx.slots.register({
          name: "conversation.session.header.views.trailing",
          id: "sig-find",
          order: 80,
          label: function () { return "Buscar"; }
        }, FindBox);
      });
    }

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }
});
