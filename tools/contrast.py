"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio) and the 3D colour pairs (CIE L*)."""


def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def Y(h):
    r, g, b = (lin(v) for v in hexrgb(h))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    ya, yb = sorted((Y(a), Y(b)), reverse=True)
    return (ya + 0.05) / (yb + 0.05)


def L(h):
    y = Y(h)
    return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16


GLASS = "#B4B3B1"  # darkest measured window glass (royal-ur / factory measure)
pairs = [
    ("正文 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("次要字 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("状态卡 Ink / CardFace", "#1B1F23", "#FBF6EC", 4.5), ("卡内次要字 / CardFace", "#3A3F44", "#FBF6EC", 4.5),
    ("当前题卡 Ink / PrimaryContainer", "#1B1F23", "#EBDDF2", 4.5), ("当前题卡次要字 / PrimaryContainer", "#3A3F44", "#EBDDF2", 4.5),
    ("主按钮字 白 / Primary", "#FFFFFF", "#7B3A98", 4.5), ("开着的提示键 白 / Primary", "#FFFFFF", "#7B3A98", 4.5),
    ("退出 / 清掉按钮字 白 / Error", "#FFFFFF", "#B3261E", 4.5), ("新纪录 白 / Primary", "#FFFFFF", "#7B3A98", 4.5),
    ("规矩页序号 白 / Primary", "#FFFFFF", "#7B3A98", 4.5), ("题名小牌 墨 / 牌面", "#2A1E14", "#F6ECD8", 4.5),
    ("木签字 墨 / 木签面", "#2A1E14", "#F2DEBC", 4.5), ("拼过的勾 Done / CardFace（图标）", "#2E7D4F", "#FBF6EC", 3.0),
    ("难度点 胡桃 / CardFace（图标）", "#5E4130", "#FBF6EC", 3.0), ("影子缩略图 / CardFace（图形）", "#231E1B", "#FBF6EC", 3.0),
    ("规矩图 枫木 / 胡桃（图形）", "#E8C99A", "#5E4130", 3.0),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |")
print("|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b)
    print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
print()
lp = [("枫木正面 vs 胡桃板面", "#E8C99A", "#5E4130", 25), ("樱桃木反面 vs 胡桃板面", "#D98A5F", "#5E4130", 15),
      ("樱桃木反面 vs 影子槽底", "#D98A5F", "#231E1B", 25), ("枫木正面 vs 樱桃木反面", "#E8C99A", "#D98A5F", 0),
      ("影子槽底 vs 胡桃板面", "#231E1B", "#5E4130", 10), ("槽边亮线 vs 胡桃板面", "#C9A57A", "#5E4130", 20),
      ("落点标记 vs 板面（要求只深半档）", "#4E3527", "#5E4130", 0), ("枫木 vs 托盘", "#E8C99A", "#6B4A34", 25)]
print("| 3D 对比 | A | B | 亮度比 | ΔL* | 门槛 ΔL* |")
print("|---|---|---|---|---|---|")
for n, a, b, t in lp:
    print(f"| {n} | {a} | {b} | {ratio(a, b):.2f}:1 | {abs(L(a) - L(b)):.1f} | {'≥ %d' % t if t else '—'} |")
