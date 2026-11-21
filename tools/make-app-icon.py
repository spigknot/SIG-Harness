#!/usr/bin/env python3
"""Gera os ícones do app (adaptativo e legado) a partir de deepseek.png.

Fonte: deepseek.png na raiz do projeto (640x640, logo branco sobre fundo
transparente). Saída:
  app/res/mipmap-<dpi>/ic_launcher_foreground.png  108dp, logo na zona segura
  app/res/mipmap-<dpi>/ic_launcher.png             48dp, ícone legado (API < 26)

O fundo do adaptativo é a cor sig_icon_bg de res/values/colors.xml.
"""
import os
import re
import math
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "deepseek.png")
RES = os.path.join(ROOT, "app", "res")
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]

ADAPTIVE_DP = 108          # tela do ícone adaptativo
SAFE_RADIUS_DP = 34.0      # todo o logo cabe no círculo de 68dp (zona segura de 66dp com folga)
LEGACY_DP = 48             # ícone legado
LEGACY_LOGO_RATIO = 0.66   # largura do logo em relação ao ícone legado


def read_bg_color():
    path = os.path.join(RES, "values", "colors.xml")
    with open(path, encoding="utf-8") as fh:
        xml = fh.read()
    m = re.search(r'<color name="sig_icon_bg">#([0-9A-Fa-f]{6})</color>', xml)
    if not m:
        raise SystemExit("sig_icon_bg nao encontrada em colors.xml")
    h = m.group(1)
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def load_logo():
    """Logo recortado no bbox do alfa, branco puro (evita franja do RGB do fundo)."""
    im = Image.open(SRC).convert("RGBA")
    alpha = im.split()[3]
    box = alpha.getbbox()
    if not box:
        raise SystemExit("deepseek.png nao tem pixels visiveis")
    alpha = alpha.crop(box)
    white = Image.new("RGBA", alpha.size, (255, 255, 255, 255))
    white.putalpha(alpha)
    return white


def max_radius(logo):
    """Maior distancia de um pixel visivel ao centro do bbox (em px da fonte)."""
    alpha = logo.split()[3]
    w, h = logo.size
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    rmax = 0.0
    px = alpha.load()
    step = 2  # amostragem suficiente: o alfa ja esta antialiasado
    for y in range(0, h, step):
        for x in range(0, w, step):
            if px[x, y] > 8:
                d = math.hypot(x - cx, y - cy)
                if d > rmax:
                    rmax = d
    return rmax


def paste_centered(canvas, logo, scale):
    """Redimensiona o logo (alfa reto, RGB constante) e centraliza no canvas."""
    w = max(1, round(logo.width * scale))
    h = max(1, round(logo.height * scale))
    scaled = logo.resize((w, h), Image.LANCZOS)
    x = (canvas.width - w) // 2
    y = (canvas.height - h) // 2
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.paste(scaled, (x, y), scaled)
    return Image.alpha_composite(canvas, layer)


def adaptive_foreground(logo, rmax, dpi):
    size = round(ADAPTIVE_DP * dpi)
    # escala em px de tela: raio seguro (dp) -> px / raio da fonte
    scale = (SAFE_RADIUS_DP / rmax) * dpi
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    return paste_centered(canvas, logo, scale)


def legacy_icon(logo, bg, dpi):
    size = round(LEGACY_DP * dpi)
    S = size * 4  # desenha em 4x para bordas lisas e reduz no final
    canvas = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    radius = round(S * 0.22)
    draw.rounded_rectangle([0, 0, S - 1, S - 1], radius=radius, fill=bg + (255,))
    scale = (LEGACY_LOGO_RATIO * S) / logo.width
    out = paste_centered(canvas, logo, scale)
    return out.resize((size, size), Image.LANCZOS)


def main():
    logo = load_logo()
    rmax = max_radius(logo)
    bg = read_bg_color()
    print("logo %dx%d, raio %.1fpx, fundo #%02X%02X%02X" % (logo.width, logo.height, rmax, bg[0], bg[1], bg[2]))
    for name, dpi in DENSITIES:
        outdir = os.path.join(RES, "mipmap-" + name)
        os.makedirs(outdir, exist_ok=True)

        fg = adaptive_foreground(logo, rmax, dpi)
        fg.save(os.path.join(outdir, "ic_launcher_foreground.png"), "PNG", optimize=True)

        lg = legacy_icon(logo, bg, dpi)
        lg.save(os.path.join(outdir, "ic_launcher.png"), "PNG", optimize=True)
        print("  %-8s foreground %4dpx  legado %3dpx" % (name, fg.width, lg.width))


if __name__ == "__main__":
    main()
