#!/usr/bin/env python3
"""把一张照片渲染成「银色雕刻硬币」的预览图。

用途：给 App 的随机事件功能出硬币贴图（猫=正面、狗=反面）。
拿到新的照片后不用改代码，调参数重跑即可：

    # 依赖
    uv run --with pillow --with numpy python tools/coin-from-photo.py 照片.jpg -o 输出.png

    # 常用参数
    --crop 0.5,0.45,0.78   裁剪窗口：中心 x, 中心 y, 边长（都是相对图宽的比例）
    --relief 0.30          浮雕高度（越大越立体，过大像揉皱的铁皮）
    --flatten 45           背景压平半径（把大面积背景压成平面，只让主体凸起）
    --light 145,42         光源：方位角(度), 仰角(度)。默认左上打光
    --size 1200            输出边长

原理：照片灰度 → 高度图 → 求法线 → 单光源着色 → 银色材质 → 圆形裁切 + 硬币边。
"""
from __future__ import annotations

import argparse
import math
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="照片 -> 银色雕刻硬币")
    p.add_argument("photo", help="输入照片")
    p.add_argument("-o", "--out", default="coin.png", help="输出 PNG")
    p.add_argument("--crop", default="0.5,0.5,1.0", help="裁剪：cx,cy,size（比例）")
    p.add_argument("--relief", type=float, default=0.30, help="浮雕高度")
    p.add_argument("--flatten", type=float, default=45.0, help="背景压平半径（0 关闭）")
    p.add_argument("--blur", type=float, default=1.6, help="细节平滑")
    p.add_argument("--light", default="145,42", help="光源 方位角,仰角（度）")
    p.add_argument("--size", type=int, default=1200, help="输出边长")
    p.add_argument("--rotate", type=float, default=0.0, help="照片旋转角度（度）")
    p.add_argument("--sharpen", type=float, default=0.0, help="锐化强度（0 关闭；低分辨率照片可试 120~200）")
    p.add_argument("--focus", default="", help="主体椭圆遮罩 cx,cy,rx,ry（比例，留空不启用）：椭圆外压平，避免背景纹理被刻上去")
    return p.parse_args()


def load_height(args: argparse.Namespace) -> np.ndarray:
    img = Image.open(args.photo).convert("L")
    if args.rotate:
        img = img.rotate(args.rotate, resample=Image.BICUBIC, expand=True)

    cx, cy, size = (float(v) for v in args.crop.split(","))
    w, h = img.size
    side = min(w, h) * size
    left = cx * w - side / 2
    top = cy * h - side / 2
    box = (int(left), int(top), int(left + side), int(top + side))
    img = img.crop(box).resize((args.size, args.size), Image.LANCZOS)

    if args.sharpen > 0:
        img = img.filter(ImageFilter.UnsharpMask(radius=3, percent=int(args.sharpen), threshold=2))
    if args.blur > 0:
        img = img.filter(ImageFilter.GaussianBlur(args.blur))

    gray = np.asarray(img, np.float32) / 255.0

    # 背景压平：减掉大尺度低频，只留主体的形状与细节，否则整张照片都会鼓起来
    if args.flatten > 0:
        base = np.asarray(img.filter(ImageFilter.GaussianBlur(args.flatten)), np.float32) / 255.0
        gray = gray - base

    # 归一化后再压缩到目标浮雕高度
    lo, hi = np.percentile(gray, 1), np.percentile(gray, 99)
    if hi - lo < 1e-6:
        hi = lo + 1e-6
    gray = np.clip((gray - lo) / (hi - lo), 0, 1)
    height = 0.5 + (gray - 0.5) * args.relief

    # 主体遮罩：椭圆之外压回平面，背景的百叶窗/墙面就不会被一起刻到硬币上
    if args.focus:
        fcx, fcy, frx, fry = (float(v) for v in args.focus.split(","))
        yy, xx = np.mgrid[0:args.size, 0:args.size]
        dx = (xx / args.size - fcx) / frx
        dy = (yy / args.size - fcy) / fry
        d = np.sqrt(dx * dx + dy * dy)
        soft = np.clip((1.0 - d) / 0.30, 0, 1)          # 边缘 30% 羽化
        soft = soft * soft * (3.0 - 2.0 * soft)         # smoothstep
        height = 0.5 + (height - 0.5) * soft
    return height


def render(height: np.ndarray, args: argparse.Namespace) -> Image.Image:
    size = args.size
    radius = size * 0.4667

    # 法线：N = normalize(-dh/dx, -dh/dy, 1/k)
    gy, gx = np.gradient(height)
    k = 26.0
    nx, ny, nz = -gx * k, -gy * k, np.ones_like(height)
    norm = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / norm, ny / norm, nz / norm

    az, elev = (math.radians(v) for v in (float(x) for x in args.light.split(",")))
    lx = math.cos(elev) * math.cos(az)
    ly = math.cos(elev) * math.sin(az)
    lz = math.sin(elev)

    diffuse = np.clip(nx * lx + ny * ly + nz * lz, 0, 1)

    # 半程向量做高光，让金属有"擦亮"的感觉
    hx, hy, hz = lx, ly, lz + 1.0
    hn = math.sqrt(hx * hx + hy * hy + hz * hz)
    hx, hy, hz = hx / hn, hy / hn, hz / hn
    spec = np.clip(nx * hx + ny * hy + nz * hz, 0, 1) ** 38

    # 银色基底 + 硬币球面感（靠近边缘压暗）
    yy, xx = np.mgrid[0:size, 0:size]
    cx = cy = size / 2
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    rim = 1.0 - 0.26 * np.clip((dist / radius) ** 3.0, 0, 1)

    lum = (0.30 + 0.78 * diffuse) * rim + 0.55 * spec * rim
    lum = np.clip(lum, 0, 1.35)
    tint = np.stack([lum * 0.945 + 0.045, lum * 0.965 + 0.045, lum * 1.020 + 0.035], -1)
    rgb = (np.clip(tint, 0, 1) * 255).astype(np.uint8)
    img = Image.fromarray(rgb, "RGB")

    # 左上柔光
    glow = Image.new("L", (size, size), 0)
    ImageDraw.Draw(glow).ellipse(
        [cx - radius * 0.78, cy - radius * 0.84, cx + radius * 0.24, cy - radius * 0.06], fill=70
    )
    img = Image.composite(
        Image.new("RGB", (size, size), (255, 255, 255)),
        img,
        glow.filter(ImageFilter.GaussianBlur(size * 0.08)).point(lambda v: int(v * 0.5)),
    )

    # 硬币边：两道刻线
    dr = ImageDraw.Draw(img)
    for rad, width in ((radius - size * 0.021, 3), (radius - size * 0.010, 2)):
        dr.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], outline=(120, 130, 144), width=width)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main() -> int:
    args = parse_args()
    height = load_height(args)
    image = render(height, args)
    image.save(args.out)
    print(f"已输出 {args.out}  ({image.size[0]}x{image.size[1]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
