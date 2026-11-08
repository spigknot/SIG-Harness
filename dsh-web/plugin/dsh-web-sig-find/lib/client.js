window.__ModuleLoader__.load({
  id: "dsh-web-sig-find",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;

    var MARK = "data-sig-find";
    var UI_ATTR = "data-sig-find-ui";
    var STORE_KEY = "sig-find-position";
    var hits = [];
    var current = -1;
    var input = null;
    var counter = null;
    var box = null;
    var launcher = null;
    var expanded = false;
    var drag = null;

    function clamp(value, low, high) { return Math.max(low, Math.min(high, value)); }

    function savedPosition() {
      try {
        var raw = window.localStorage.getItem(STORE_KEY);
        if (raw === null) return null;
        var parsed = JSON.parse(raw);
        if (typeof parsed.top !== "number" || typeof parsed.left !== "number") return null;
        return parsed;
      } catch (e) { return null; }
    }

    function savePosition(top, left) {
      try { window.localStorage.setItem(STORE_KEY, JSON.stringify({ top: top, left: left })); } catch (e) { /* modo restrito */ }
    }

    function scope() {
      var tabs = document.querySelector("[data-conversation-tabs]");
      var el = tabs;
      while (el !== null && el !== document.body) {
        var cls = typeof el.className === "string" ? el.className : "";
        if (cls.indexOf("_root") !== -1) return el;
        el = el.parentElement;
      }
      return document.body;
    }

    function clearMarks() {
      var marks = document.querySelectorAll("mark[" + MARK + "]");
      for (var i = 0; i < marks.length; i += 1) {
        var mark = marks[i];
        var parent = mark.parentNode;
        if (parent === null) continue;
        while (mark.firstChild !== null) parent.insertBefore(mark.firstChild, mark);
        parent.removeChild(mark);
        parent.normalize();
      }
      hits = [];
      current = -1;
    }

    function collect(node, out) {
      if (node.nodeType === 3) {
        var value = node.nodeValue;
        if (value !== null && value.trim() !== "") out.push(node);
        return;
      }
      if (node.nodeType !== 1) return;
      var tag = node.tagName;
      if (tag === "SCRIPT" || tag === "STYLE" || tag === "MARK" || tag === "INPUT" || tag === "TEXTAREA") return;
      if (node.hasAttribute(UI_ATTR)) return;
      var cls = typeof node.className === "string" ? node.className : "";
      if (cls.indexOf("_header") !== -1) return;
      var children = node.childNodes;
      for (var i = 0; i < children.length; i += 1) collect(children[i], out);
    }

    function highlight(root, needle) {
      var lower = needle.toLowerCase();
      var nodes = [];
      collect(root, nodes);
      for (var n = 0; n < nodes.length; n += 1) {
        var node = nodes[n];
        var text = node.nodeValue;
        var hay = text.toLowerCase();
        var at = hay.indexOf(lower);
        if (at === -1) continue;
        var frag = document.createDocumentFragment();
        var cursor = 0;
        while (at !== -1) {
          if (at > cursor) frag.appendChild(document.createTextNode(text.slice(cursor, at)));
          var mark = document.createElement("mark");
          mark.setAttribute(MARK, "1");
          mark.style.background = "#f5c45155";
          mark.style.color = "inherit";
          mark.style.borderRadius = "2px";
          mark.textContent = text.slice(at, at + lower.length);
          frag.appendChild(mark);
          hits.push(mark);
          cursor = at + lower.length;
          at = hay.indexOf(lower, cursor);
        }
        if (cursor < text.length) frag.appendChild(document.createTextNode(text.slice(cursor)));
        node.parentNode.replaceChild(frag, node);
      }
    }

    function show(index) {
      if (hits.length === 0) return;
      if (current >= 0 && current < hits.length) {
        hits[current].style.background = "#f5c45155";
        hits[current].style.color = "inherit";
      }
      current = ((index % hits.length) + hits.length) % hits.length;
      var mark = hits[current];
      mark.style.background = "#f5c451aa";
      mark.style.color = "#111";
      mark.scrollIntoView({ block: "center", behavior: "smooth" });
      if (counter !== null) counter.textContent = (current + 1) + "/" + hits.length;
    }

    function run(query, jump, attempts) {
      var needle = (query || "").trim();
      clearMarks();
      if (counter !== null) counter.textContent = needle === "" ? "" : "0";
      if (needle === "") return;
      highlight(scope(), needle);
      if (counter !== null) counter.textContent = hits.length === 0 ? "0" : "1/" + hits.length;
      if (hits.length === 0) {
        if (attempts > 0) window.setTimeout(function () { run(needle, jump, attempts - 1); }, 300);
        return;
      }
      show(jump === "last" ? hits.length - 1 : 0);
    }

    /** Altura da faixa visível no ponto (x, y); ignora contêineres altos e os próprios widgets. */
    function rowBottomAt(x, y) {
      var stack = [];
      try { stack = document.elementsFromPoint(x, y) || []; } catch (e) { return null; }
      for (var i = 0; i < stack.length; i += 1) {
        var el = stack[i];
        if (el === launcher || el === box) continue;
        if (typeof el.closest === "function" && el.closest("[" + UI_ATTR + "]") !== null) continue;
        var rect = el.getBoundingClientRect();
        if (rect.height <= 0 || rect.height > 240) continue;
        if (rect.bottom <= y - 2) continue;
        return rect.bottom;
      }
      return null;
    }

    /** Folga extra abaixo da faixa (ajuste fino de posição). */
    var DROP = 20;

    /** Ponto padrão: logo abaixo da faixa que ocupa a linha seguinte às abas. */
    function defaultTop(tabs) {
      var rect = tabs.getBoundingClientRect();
      var edge = rect.bottom + 6;
      var y = edge + 6;
      var bottom = rowBottomAt(Math.round(window.innerWidth * 0.5), y);
      if (bottom === null) bottom = rowBottomAt(Math.round(window.innerWidth - 30), y);
      if (bottom !== null) {
        var target = bottom + 8 + DROP;
        if (target > edge && target <= edge + 160) return target;
      }
      return edge + DROP;
    }

    function applyFrame(top, left, width) {
      if (launcher !== null) {
        launcher.style.top = top + "px";
        launcher.style.left = left + "px";
        launcher.style.right = "auto";
        launcher.style.display = expanded ? "none" : "flex";
      }
      if (box !== null) {
        box.style.top = top + "px";
        box.style.right = "12px";
        box.style.left = "auto";
        box.style.display = expanded ? "flex" : "none";
        if (width === true) box.style.right = (window.innerWidth - left - 26) + "px";
      }
    }

    function place() {
      var tabs = document.querySelector("[data-conversation-tabs]");
      if (tabs === null) { hideWidgets(); return; }
      var rect = tabs.getBoundingClientRect();
      if (rect.width === 0) { hideWidgets(); return; }

      var saved = savedPosition();
      if (saved !== null) {
        applyFrame(clamp(saved.top, 4, window.innerHeight - 32), clamp(saved.left, 4, window.innerWidth - 32), true);
        return;
      }
      applyFrame(defaultTop(tabs), Math.max(8, window.innerWidth - 38), false);
    }

    function hideWidgets() {
      if (box !== null) box.style.display = "none";
      if (launcher !== null) launcher.style.display = "none";
    }

    function expand() {
      expanded = true;
      place();
      if (input !== null) { input.focus(); input.select(); }
    }

    function collapse() {
      expanded = false;
      if (input !== null) input.value = "";
      if (counter !== null) counter.textContent = "";
      clearMarks();
      place();
    }

    function makeButton(label, title, onClick) {
      var button = document.createElement("button");
      button.type = "button";
      button.textContent = label;
      button.title = title;
      button.setAttribute(UI_ATTR, "1");
      button.style.cssText = "border:1px solid #2c2e33;background:transparent;color:#f2f2f2;border-radius:6px;"
        + "width:22px;height:22px;font-size:11px;padding:0;cursor:pointer";
      button.addEventListener("click", onClick);
      return button;
    }

    /** Arrastar move a lupa; soltar grava a posição. Um toque curto abre a busca. */
    function installDrag() {
      launcher.addEventListener("pointerdown", function (event) {
        var rect = launcher.getBoundingClientRect();
        drag = { id: event.pointerId, x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, moved: false };
        try { launcher.setPointerCapture(event.pointerId); } catch (e) { /* sem captura */ }
      });
      launcher.addEventListener("pointermove", function (event) {
        if (drag === null || event.pointerId !== drag.id) return;
        var dx = event.clientX - drag.x;
        var dy = event.clientY - drag.y;
        if (drag.moved !== true && Math.abs(dx) + Math.abs(dy) < 8) return;
        drag.moved = true;
        var left = clamp(drag.left + dx, 4, window.innerWidth - 32);
        var top = clamp(drag.top + dy, 4, window.innerHeight - 32);
        applyFrame(top, left, true);
      });
      launcher.addEventListener("pointerup", function (event) {
        if (drag === null || event.pointerId !== drag.id) return;
        var moved = drag.moved;
        drag = null;
        if (moved) {
          var rect = launcher.getBoundingClientRect();
          savePosition(Math.round(rect.top), Math.round(rect.left));
          place();
        } else {
          expand();
        }
      });
      launcher.addEventListener("pointercancel", function () { drag = null; });
    }

    function build() {
      if (document.getElementById("sig-find-root") !== null) return;

      launcher = document.createElement("button");
      launcher.id = "sig-find-launcher";
      launcher.type = "button";
      launcher.title = "Buscar nesta sessão (arraste para reposicionar)";
      launcher.setAttribute(UI_ATTR, "1");
      launcher.setAttribute("aria-label", "Buscar nesta sessão");
      launcher.style.cssText = "position:fixed;display:none;z-index:2147483000;width:26px;height:26px;"
        + "align-items:center;justify-content:center;padding:0;cursor:pointer;border-radius:50%;"
        + "background:#1b1c1f;border:1px solid #2c2e33;color:#b9bcc4;box-shadow:0 2px 8px rgba(0,0,0,.4);"
        + "touch-action:none;user-select:none";
      launcher.innerHTML = '<svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" '
        + 'stroke-width="1.7" stroke-linecap="round"><circle cx="7" cy="7" r="4.2"/><path d="M10.3 10.3L14 14"/></svg>';

      box = document.createElement("div");
      box.id = "sig-find-root";
      box.setAttribute(UI_ATTR, "1");
      box.style.cssText = "position:fixed;display:none;align-items:center;gap:6px;z-index:2147483000;padding:4px 8px;"
        + "border-radius:8px;background:#1b1c1f;border:1px solid #2c2e33;box-shadow:0 2px 10px rgba(0,0,0,.45)";

      input = document.createElement("input");
      input.type = "text";
      input.placeholder = "Buscar nesta sessão";
      input.setAttribute(UI_ATTR, "1");
      input.style.cssText = "width:140px;height:22px;font-size:12px;color:#f2f2f2;background:#151517;"
        + "border:1px solid #2c2e33;border-radius:6px;padding:0 8px";
      input.addEventListener("input", function () { run(input.value, "first", 0); });
      input.addEventListener("keydown", function (event) {
        if (event.key === "Enter") { event.preventDefault(); run(input.value, "last", 0); }
        if (event.key === "Escape") { event.preventDefault(); collapse(); }
      });

      counter = document.createElement("span");
      counter.setAttribute(UI_ATTR, "1");
      counter.style.cssText = "font-size:11px;color:#8b8f98;min-width:34px;text-align:center";

      box.appendChild(input);
      box.appendChild(counter);
      box.appendChild(makeButton("↑", "Ocorrência anterior", function () { show(current - 1); }));
      box.appendChild(makeButton("↓", "Próxima ocorrência", function () { show(current + 1); }));
      box.appendChild(makeButton("×", "Fechar busca", collapse));

      document.body.appendChild(box);
      document.body.appendChild(launcher);
      installDrag();

      launcher.style.transition = "opacity .18s ease";
      launcher.style.opacity = "0.15";
      window.setTimeout(function () { launcher.style.opacity = "1"; }, 60);

      window.addEventListener("resize", place);
      document.addEventListener("scroll", place, true);
      window.setInterval(place, 800);
      place();
    }

    function hookSidebar() {
      if (window.__sigFindHooked === true) return;
      window.__sigFindHooked = true;
      document.addEventListener("click", function (event) {
        var target = event.target;
        if (target === null || target instanceof Element !== true) return;
        if (target.closest("[class*='searchTree']") === null) return;
        var field = document.querySelector("[class*='searchInput']");
        var query = field !== null && typeof field.value === "string" ? field.value.trim() : "";
        if (query === "") return;
        window.setTimeout(function () {
          if (input !== null) input.value = query;
          expand();
          run(query, "last", 12);
        }, 250);
      }, true);
    }

    function apply() {
      build();
      hookSidebar();
    }

    exports.apply = apply;
    exports.inject = [];
    return module.exports;
  }
});
