#!/usr/bin/env python3
"""按「外圈刻线 + 顶部图形 + 环形文字 + 中央主图」的纪念币排版，直接生成银币正反面。

不依赖照片：先在灰度图上画高度图（亮=凸起、暗=凹刻），再走单光源浮雕渲染成银色。

    uv run --with pillow --with numpy python tools/coin-make.py -o dist/coin --size 1000

输出：<前缀>-front.png（正面/字面）、<前缀>-back.png（反面/标志面）、<前缀>-preview.png（对比图）

接进 App：把 front/back 缩到 720px 存成
    android/app/src/main/res/drawable-nodpi/coin_heads.png
    android/app/src/main/res/drawable-nodpi/coin_tails.png
CoinView 会直接用这两张贴图做「上抛 + 压缩翻转」动画。
"""
from __future__ import annotations

import argparse
import math
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONT_CJK = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"
FONT_LATIN = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

BASE = 100      # 盘面基准高度
RAISED = 190    # 一般凸起
HIGH = 242      # 主要凸起（主图与文字）
EDGE = 252      # 外圈凸边


# ---------------------------------------------------------------- 基础工具

def circle(radius: float, cx: float, cy: float) -> list[float]:
    return [cx - radius, cy - radius, cx + radius, cy + radius]


def stamp(base: Image.Image, layer: Image.Image, pos: tuple[float, float], angle: float = 0.0) -> None:
    """把一个图层按 [angle] 旋转后叠加（取较亮处，不覆盖既有内容）。"""
    if abs(angle) > 0.01:
        layer = layer.rotate(-angle, resample=Image.BICUBIC, center=(layer.width / 2, layer.height / 2))
    x = int(pos[0] - layer.width / 2)
    y = int(pos[1] - layer.height / 2)
    region = base.crop((x, y, x + layer.width, y + layer.height))
    base.paste(ImageChops.lighter(region, layer), (x, y))


def arc_text(base: Image.Image, text: str, font: ImageFont.FreeTypeFont, cx: float, cy: float,
             radius: float, start_deg: float, end_deg: float, value: int, upside_down: bool = False) -> None:
    """沿圆弧排版文字。upside_down=False 用于上半圈（字底朝圆心），True 用于下半圈。"""
    if not text:
        return
    widths = [font.getlength(ch) for ch in text]
    total = sum(widths) or 1.0
    box = int(font.size * 2.4)
    acc = 0.0
    for i, ch in enumerate(text):
        centre = (acc + widths[i] / 2) / total     # 按字宽定位，避免中英文混排时疏密不均
        acc += widths[i]
        if ch == " ":
            continue
        t = centre
        deg = start_deg + (end_deg - start_deg) * t
        ang = math.radians(deg)
        layer = Image.new("L", (box, box), 0)
        ImageDraw.Draw(layer).text((box / 2, box / 2), ch, font=font, fill=255, anchor="mm")
        pos = (cx + radius * math.cos(ang), cy + radius * math.sin(ang))
        stamp(base, layer, pos, deg + (90 if not upside_down else -90))


def extract_logo_mask(path: str):
    """从 logo 图片里抠出图形本体（优先找彩色标志，找不到就取深色图案），返回紧贴内容的 L mask。"""
    if not path:
        return None
    src = Image.open(path).convert("RGB")
    arr = np.asarray(src).astype(np.int16)
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    mask = (r - g > 55) & (r - b > 55) & (r > 100)          # 红色标志
    if mask.sum() < 800:
        gray = np.asarray(src.convert("L")).astype(np.int16)
        mask = gray < 140                                    # 没有彩色就取深色笔画
    ys, xs = np.where(mask)
    if len(ys) == 0:
        return None
    crop = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return Image.fromarray((crop * 255).astype(np.uint8), "L")


def paste_logo(base: Image.Image, mask, cx: float, cy: float, height: float, value: int) -> None:
    """把 logo mask 等比缩放到指定高度，贴到高度图上（取较亮处，不覆盖已有内容）。"""
    if mask is None:
        return
    w = max(1, int(height * mask.width / mask.height))
    scaled = mask.resize((w, int(height)), Image.LANCZOS).point(lambda v: int(v / 255 * value))
    layer = Image.new("L", (w, int(height)), 0)
    layer.paste(scaled, (0, 0))
    x, y = int(cx - w / 2), int(cy - height / 2)
    region = base.crop((x, y, x + w, y + int(height)))
    base.paste(ImageChops.lighter(region, layer), (x, y))


def bezier(p0, p1, p2, steps: int = 24):
    """二次贝塞尔采样，用来画平滑的羊角弧线。"""
    pts = []
    for i in range(steps + 1):
        t = i / steps
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t ** 2 * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t ** 2 * p2[1]
        pts.append((x, y))
    return pts


def draw_logo(d: ImageDraw.ImageDraw, cx: float, cy: float, size: float, fill: int) -> None:
    """风格化的「羊角」：两条从中心向上外张、末端略微内收的粗弧。"""
    s = size
    w = max(5, int(s * 0.215))
    left = bezier((cx - s * 0.015, cy + s * 0.50), (cx - s * 0.60, cy + s * 0.02), (cx - s * 0.40, cy - s * 0.66))
    right = bezier((cx + s * 0.015, cy + s * 0.50), (cx + s * 0.60, cy + s * 0.02), (cx + s * 0.40, cy - s * 0.66))
    for curve in (left, right):
        d.line(curve, fill=fill, width=w, joint="curve")


# ---------------------------------------------------------------- 高度图

def front_height(size: int, logo=None) -> Image.Image:
    """正面（字面）：外圈刻线 + 三枚小印章 + 环形文字 + 中央主字。"""
    cx = cy = size / 2
    r = size * 0.47
    img = Image.new("L", (size, size), BASE)
    d = ImageDraw.Draw(img)

    d.ellipse(circle(r, cx, cy), outline=EDGE, width=max(4, int(r * 0.058)))
    for k in (0.935, 0.895):
        d.ellipse(circle(r * k, cx, cy), outline=RAISED, width=max(2, int(r * 0.013)))

    # 顶部三枚小印章
    font_seal = ImageFont.truetype(FONT_CJK, int(r * 0.145), index=0)
    for i, ch in enumerate("福進財"):
        deg = 250 + i * 20
        ang = math.radians(deg)
        x, y = cx + r * 0.655 * math.cos(ang), cy + r * 0.655 * math.sin(ang)
        s = r * 0.165
        d.rounded_rectangle([x - s / 2, y - s / 2, x + s / 2, y + s / 2], radius=s * 0.18,
                            outline=RAISED, width=max(2, int(r * 0.013)))
        d.text((x, y), ch, font=font_seal, fill=HIGH, anchor="mm")

    # 环形文字
    arc_text(img, "SINGLE  JOURNEY  TICKET", ImageFont.truetype(FONT_LATIN, int(r * 0.115)),
             cx, cy, r * 0.80, 203, 337, HIGH)
    arc_text(img, "国有资产 出站回收", ImageFont.truetype(FONT_CJK, int(r * 0.115), index=0),
             cx, cy, r * 0.80, 157, 23, HIGH, upside_down=True)

    # 中央：只放字
    d.text((cx, cy - r * 0.045), "广州地铁", font=ImageFont.truetype(FONT_CJK, int(r * 0.255), index=0),
           fill=HIGH, anchor="mm")
    d.text((cx, cy + r * 0.155), "Guangzhou Metro", font=ImageFont.truetype(FONT_LATIN, int(r * 0.098)),
           fill=RAISED, anchor="mm")
    return img


def back_height(size: int, logo=None) -> Image.Image:
    """反面（logo 面）：外圈刻线 + 中央 logo + 环形文字 + 底部中文。"""
    cx = cy = size / 2
    r = size * 0.47
    img = Image.new("L", (size, size), BASE)
    d = ImageDraw.Draw(img)

    d.ellipse(circle(r, cx, cy), outline=EDGE, width=max(4, int(r * 0.058)))
    for k in (0.935, 0.895):
        d.ellipse(circle(r * k, cx, cy), outline=RAISED, width=max(2, int(r * 0.013)))

    # 中央：logo（有真标志就用真标志，否则用手绘造型兜底）
    if logo is not None:
        paste_logo(img, logo, cx, cy - r * 0.075, r * 0.62, HIGH)
    else:
        draw_logo(d, cx, cy - r * 0.075, r * 0.52, HIGH)

    # 环形文字
    arc_text(img, "SINGLE  JOURNEY  TICKET", ImageFont.truetype(FONT_LATIN, int(r * 0.105)),
             cx, cy, r * 0.815, 203, 337, RAISED)
    arc_text(img, "出站回收", ImageFont.truetype(FONT_CJK, int(r * 0.105), index=0),
             cx, cy, r * 0.815, 111, 69, RAISED, upside_down=True)

    # 左右对称的四个小圆点
    dot = r * 0.028
    for deg in (168, 200, 340, 12):
        ang = math.radians(deg)
        x, y = cx + r * 0.585 * math.cos(ang), cy + r * 0.585 * math.sin(ang)
        d.ellipse(circle(dot, x, y), fill=RAISED)
    return img


# ---------------------------------------------------------------- 浮雕渲染

def render(height_img: Image.Image, size: int, relief: float = 0.55) -> Image.Image:
    """灰度高度图 -> 单光源银色浮雕。"""
    # 真实雕刻的边缘是圆角的：先给高度图做轻微模糊，法线才有平滑的过渡
    softened = height_img.filter(ImageFilter.GaussianBlur(2.6))
    h = np.asarray(softened, np.float32) / 255.0
    h = 0.5 + (h - 0.5) * relief

    radius = size * 0.4667
    yy, xx = np.mgrid[0:size, 0:size]
    cx = cy = size / 2

    gy, gx = np.gradient(h)
    k = 30.0
    nx, ny, nz = -gx * k, -gy * k, np.ones_like(h)
    norm = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / norm, ny / norm, nz / norm

    az, elev = math.radians(145.0), math.radians(42.0)
    lx, ly, lz = math.cos(elev) * math.cos(az), math.cos(elev) * math.sin(az), math.sin(elev)
    diffuse = np.clip(nx * lx + ny * ly + nz * lz, 0, 1)

    hx, hy, hz = lx, ly, lz + 1.0
    hn = math.sqrt(hx * hx + hy * hy + hz * hz)
    spec = np.clip(nx * (hx / hn) + ny * (hy / hn) + nz * (hz / hn), 0, 1) ** 42

    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    rim = 1.0 - 0.26 * np.clip((dist / radius) ** 3.0, 0, 1)

    lum = np.clip((0.27 + 0.63 * diffuse) * rim + 0.30 * spec * rim, 0, 1.15)
    tint = np.stack([lum * 0.945 + 0.045, lum * 0.965 + 0.045, lum * 1.020 + 0.035], -1)
    img = Image.fromarray((np.clip(tint, 0, 1) * 255).astype(np.uint8), "RGB")

    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse([cx - radius * 0.78, cy - radius * 0.84, cx + radius * 0.24, cy - radius * 0.06], fill=70)
    img = Image.composite(Image.new("RGB", (size, size), (255, 255, 255)), img,
                          glow.filter(ImageFilter.GaussianBlur(size * 0.08)).point(lambda v: int(v * 0.5)))

    dr = ImageDraw.Draw(img)
    for rad, w in ((radius - size * 0.021, 3), (radius - size * 0.010, 2)):
        dr.ellipse(circle(rad, cx, cy), outline=(120, 130, 144), width=w)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse(circle(radius, cx, cy), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main() -> int:
    p = argparse.ArgumentParser(description="直接生成银币正反面")
    p.add_argument("-o", "--out-prefix", default="dist/coin", help="输出前缀")
    p.add_argument("--size", type=int, default=1000, help="输出边长")
    p.add_argument("--relief", type=float, default=0.72, help="浮雕高度")
    p.add_argument("--logo", default="", help="logo 图片路径（从图里抠出形状做浮雕；留空则用手绘造型）")
    args = p.parse_args()

    logo = extract_logo_mask(args.logo)
    if args.logo:
        print(f"logo: {'已提取形状' if logo is not None else '未识别到图形，改用手绘造型'}"
              + (f"  ({logo.width}x{logo.height})" if logo is not None else ""))
    front = render(front_height(args.size, logo), args.size, args.relief)
    back = render(back_height(args.size, logo), args.size, args.relief)
    front.save(f"{args.out_prefix}-front.png")
    back.save(f"{args.out_prefix}-back.png")

    # 并排对比
    gap, pad = 16, 24
    half = args.size // 2
    f = front.resize((half, half), Image.LANCZOS)
    b = back.resize((half, half), Image.LANCZOS)
    canvas = Image.new("RGB", (half * 2 + gap + pad * 2, half + pad * 2 + 44), (15, 20, 32))
    canvas.paste(f, (pad, pad), f)
    canvas.paste(b, (pad + half + gap, pad), b)
    canvas.save(f"{args.out_prefix}-preview.png")

    print(f"已输出 {args.out_prefix}-front.png / -back.png / -preview.png")
    return 0


if __name__ == "__main__":
    sys.exit(main())
