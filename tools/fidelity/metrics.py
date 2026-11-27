"""Pure measures for the fidelity report: colour difference, element pairing, SSIM and the verdict.

See docs/fidelity.md for what each number means and the thresholds.
"""
import math
import re
from collections import Counter, defaultdict

import numpy as np

# Pass thresholds (docs/fidelity.md).
TOLERANCE_DP = 2.0
MIN_GEOMETRY = 0.95
MAX_DELTA_E = 3.0
MIN_SSIM = 0.92


# ---- colour -------------------------------------------------------------------------------

def css_rgba(value):
    """(r, g, b, alpha) from a computed CSS colour such as 'rgb(1, 2, 3)'; None for keywords."""
    m = re.fullmatch(r"rgba?\(\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\s*\)", (value or "").strip())
    if not m:
        return None
    r, g, b = (int(round(float(v))) for v in m.groups()[:3])
    return r, g, b, float(m.group(4)) if m.group(4) is not None else 1.0


def srgb_to_lab(rgb):
    """CIE L*a*b* (D65) of an 8-bit sRGB colour."""
    def lin(c):
        c /= 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(float(c)) for c in rgb[:3])
    x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
    y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.0
    z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > (6 / 29) ** 3 else t / (3 * (6 / 29) ** 2) + 4 / 29
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def ciede2000(lab1, lab2):
    """CIEDE2000 colour difference with kL = kC = kH = 1."""
    L1, a1, b1 = lab1
    L2, a2, b2 = lab2
    c_bar = (math.hypot(a1, b1) + math.hypot(a2, b2)) / 2
    g = 0.5 * (1 - math.sqrt(c_bar ** 7 / (c_bar ** 7 + 25 ** 7)))
    a1p, a2p = (1 + g) * a1, (1 + g) * a2
    c1p, c2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360 if c1p else 0.0
    h2p = math.degrees(math.atan2(b2, a2p)) % 360 if c2p else 0.0

    dLp = L2 - L1
    dCp = c2p - c1p
    if c1p * c2p == 0:
        dhp = 0.0
    else:
        dhp = h2p - h1p
        if dhp > 180:
            dhp -= 360
        elif dhp < -180:
            dhp += 360
    dHp = 2 * math.sqrt(c1p * c2p) * math.sin(math.radians(dhp) / 2)

    Lp = (L1 + L2) / 2
    Cp = (c1p + c2p) / 2
    if c1p * c2p == 0:
        hp = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        hp = (h1p + h2p) / 2
    else:
        hp = (h1p + h2p + 360) / 2 if h1p + h2p < 360 else (h1p + h2p - 360) / 2

    t = (1 - 0.17 * math.cos(math.radians(hp - 30)) + 0.24 * math.cos(math.radians(2 * hp))
         + 0.32 * math.cos(math.radians(3 * hp + 6)) - 0.20 * math.cos(math.radians(4 * hp - 63)))
    d_theta = 30 * math.exp(-(((hp - 275) / 25) ** 2))
    rc = 2 * math.sqrt(Cp ** 7 / (Cp ** 7 + 25 ** 7))
    sl = 1 + 0.015 * (Lp - 50) ** 2 / math.sqrt(20 + (Lp - 50) ** 2)
    sc = 1 + 0.045 * Cp
    sh = 1 + 0.015 * Cp * t
    rt = -math.sin(math.radians(2 * d_theta)) * rc
    return math.sqrt((dLp / sl) ** 2 + (dCp / sc) ** 2 + (dHp / sh) ** 2 + rt * (dCp / sc) * (dHp / sh))


def delta_e(rgb1, rgb2):
    """CIEDE2000 difference of two 8-bit sRGB colours."""
    return ciede2000(srgb_to_lab(rgb1), srgb_to_lab(rgb2))


def dominant_colour(img, box, inset):
    """The most frequent colour of an RGB array inside box (x, y, w, h in pixels), inset on every side."""
    x, y, w, h = box
    x0, y0 = int(round(x + inset)), int(round(y + inset))
    x1, y1 = int(round(x + w - inset)), int(round(y + h - inset))
    x0, y0 = max(x0, 0), max(y0, 0)
    x1, y1 = min(x1, img.shape[1]), min(y1, img.shape[0])
    if x1 <= x0 or y1 <= y0:
        return None
    return _most_common(img[y0:y1, x0:x1, :3].reshape(-1, 3))


def edge_colour(img, box, scale, near=1.5, far=4.0):
    """The fill of an element: the most frequent colour in a band just inside its edges, between
    near and far dp in, over the middle half of each side. The band misses a 1 dp border, rounded
    corners and, for a container, the children inside its padding. box is in pixels; scale is px per dp."""
    x, y, w, h = box
    a = near * scale
    b = min(far * scale, w / 2, h / 2)
    if b <= a:
        return dominant_colour(img, box, inset=0)
    strips = [
        (x + w / 4, y + a, w / 2, b - a), (x + w / 4, y + h - b, w / 2, b - a),
        (x + a, y + h / 4, b - a, h / 2), (x + w - b, y + h / 4, b - a, h / 2),
    ]
    pixels = []
    for sx, sy, sw, sh in strips:
        x0, y0 = max(int(round(sx)), 0), max(int(round(sy)), 0)
        x1, y1 = min(int(round(sx + sw)), img.shape[1]), min(int(round(sy + sh)), img.shape[0])
        if x1 > x0 and y1 > y0:
            pixels.append(img[y0:y1, x0:x1, :3].reshape(-1, 3))
    if not pixels:
        return None
    return _most_common(np.concatenate(pixels))


def _most_common(pixels):
    packed = (pixels[:, 0].astype(np.int64) << 16) | (pixels[:, 1].astype(np.int64) << 8) | pixels[:, 2]
    value, _ = Counter(packed.tolist()).most_common(1)[0]
    return (value >> 16) & 255, (value >> 8) & 255, value & 255


# ---- elements -------------------------------------------------------------------------------

def pair(board, app):
    """Pairs board elements (by 'name') with app nodes (by 'tag'): the n-th of a name with the n-th of the tag,
    both in document / tree order. Returns (pairs, missing board elements, extra app nodes)."""
    by_tag = defaultdict(list)
    for node in app:
        by_tag[node["tag"]].append(node)
    used = Counter()
    pairs, missing = [], []
    for el in board:
        nodes = by_tag.get(el["name"], [])
        i = used[el["name"]]
        if i < len(nodes):
            pairs.append((el, nodes[i]))
            used[el["name"]] += 1
        else:
            missing.append(el)
    extra = [n for tag, nodes in by_tag.items() for n in nodes[used[tag]:]]
    return pairs, missing, extra


def within(board_el, app_node, tolerance=TOLERANCE_DP):
    """Whether position and size agree within tolerance dp on every edge measure."""
    return all(abs(board_el[k] - app_node[k]) <= tolerance + 1e-9 for k in ("x", "y", "w", "h"))


# ---- whole screen -------------------------------------------------------------------------

SSIM_WINDOW = 11
SSIM_RADIUS = SSIM_WINDOW // 2


def _gaussian(sigma=1.5, size=SSIM_WINDOW):
    x = np.arange(size) - size // 2
    k = np.exp(-(x ** 2) / (2 * sigma ** 2))
    return k / k.sum()


def _blur(a, k):
    """Separable Gaussian blur with edge padding, same shape out."""
    r = len(k) // 2
    p = np.pad(a, ((r, r), (0, 0)), mode="edge")
    a = sum(k[i] * p[i:i + a.shape[0], :] for i in range(len(k)))
    p = np.pad(a, ((0, 0), (r, r)), mode="edge")
    return sum(k[i] * p[:, i:i + a.shape[1]] for i in range(len(k)))


def luma(img):
    """Rec. 601 luma of an RGB uint8 array, as float64 0..255."""
    f = img[..., :3].astype(np.float64)
    return 0.299 * f[..., 0] + 0.587 * f[..., 1] + 0.114 * f[..., 2]


def ssim(a, b):
    """Mean SSIM (Wang et al. 2004; Gaussian window 11, sigma 1.5) of two same-size RGB images, and its map."""
    x, y = luma(a), luma(b)
    k = _gaussian()
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    mx, my = _blur(x, k), _blur(y, k)
    sxx = _blur(x * x, k) - mx * mx
    syy = _blur(y * y, k) - my * my
    sxy = _blur(x * y, k) - mx * my
    smap = ((2 * mx * my + c1) * (2 * sxy + c2)) / ((mx * mx + my * my + c1) * (sxx + syy + c2))
    return float(smap.mean()), smap


def text_mask(shape, text_boxes, scale):
    """Boolean mask (H, W) of pixels inside text_boxes ({x, y, w, h} in dp), scaled by scale px per dp."""
    h, w = shape
    mask = np.zeros((h, w), dtype=bool)
    for box in text_boxes:
        x0 = max(0, int(round(box["x"] * scale)))
        y0 = max(0, int(round(box["y"] * scale)))
        x1 = min(w, int(round((box["x"] + box["w"]) * scale)))
        y1 = min(h, int(round((box["y"] + box["h"]) * scale)))
        if x1 > x0 and y1 > y0:
            mask[y0:y1, x0:x1] = True
    return mask


def dilate_mask(mask, radius):
    """Expands True pixels in a 2D boolean mask by radius px in x and y (a (2*radius+1) square window)."""
    if radius <= 0 or not mask.any():
        return mask.copy()
    h, w = mask.shape
    v = mask.copy()
    for d in range(1, min(radius + 1, h)):
        v[d:, :] |= mask[:-d, :]
        v[:-d, :] |= mask[d:, :]
    out = v.copy()
    for d in range(1, min(radius + 1, w)):
        out[:, d:] |= v[:, :-d]
        out[:, :-d] |= v[:, d:]
    return out


def split_ssim(smap, mask, margin=0):
    """Mean SSIM outside mask (expanded by margin px so SSIM windows do not read masked pixels)
    and inside mask (text), each None if its region is empty."""
    excluded = dilate_mask(mask, margin) if margin > 0 else mask
    non_text = float(smap[~excluded].mean()) if (~excluded).any() else None
    text = float(smap[mask].mean()) if mask.any() else None
    return non_text, text


# ---- previews -------------------------------------------------------------------------------

def clip_board(board, img, name, index, dsf, slack=0.5):
    """The part of a board inside its index-th element named name, as a board of its own: the elements that lie
    within it (slack dp allowed), moved so that it starts at 0, 0, and the image cut to it. A preview board draws
    several devices side by side (the splash's board Main); each device's screen is compared on its own."""
    frames = [e for e in board["elements"] if e["name"] == name]
    if index >= len(frames):
        raise ValueError(f"{board.get('board')}: no element {name} #{index}")
    c = frames[index]

    def inside(e):
        return (e["x"] >= c["x"] - slack and e["y"] >= c["y"] - slack
                and e["x"] + e["w"] <= c["x"] + c["w"] + slack and e["y"] + e["h"] <= c["y"] + c["h"] + slack)
    elements = [{**e, "x": round(e["x"] - c["x"], 2), "y": round(e["y"] - c["y"], 2)} for e in board["elements"] if inside(e)]
    out = {**board, "elements": elements, "size": [c["w"], c["h"]]}
    if "text_boxes" in board:
        clipped_boxes = []
        for b in board["text_boxes"]:
            x0, y0 = max(b["x"], c["x"]), max(b["y"], c["y"])
            x1, y1 = min(b["x"] + b["w"], c["x"] + c["w"]), min(b["y"] + b["h"], c["y"] + c["h"])
            if x1 > x0 and y1 > y0:
                clipped_boxes.append({"x": round(x0 - c["x"], 2), "y": round(y0 - c["y"], 2),
                                      "w": round(x1 - x0, 2), "h": round(y1 - y0, 2)})
        out["text_boxes"] = clipped_boxes
    x0, y0 = int(round(c["x"] * dsf)), int(round(c["y"] * dsf))
    x1, y1 = int(round((c["x"] + c["w"]) * dsf)), int(round((c["y"] + c["h"]) * dsf))
    return out, img[y0:y1, x0:x1]


# ---- screens and verdict --------------------------------------------------------------------

_PREFIX = {"Set": "settings", "Start": "start", "Ws": "workspace"}


def screen_of(board):
    """The screen a device board shows: SetBarUpdates -> settings-updates. None for block boards."""
    m = re.fullmatch(r"(Set|Start|Ws)(Bar|Closed|Open)(\w+)", board)
    if not m:
        return None
    rest = re.sub(r"(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Z])", "-", m.group(3)).lower()
    return f"{_PREFIX[m.group(1)]}-{rest}"


def verdict(tokens, geometry, missing, max_delta_e, ssim):
    """Pass when every threshold holds; reasons name each one that does not."""
    reasons = []
    if tokens is None or tokens < 1.0:
        reasons.append("tokens below 100%" if tokens is not None else "tokens not measured")
    if geometry < MIN_GEOMETRY:
        reasons.append(f"geometry {geometry:.0%} < {MIN_GEOMETRY:.0%}")
    if missing:
        reasons.append(f"{missing} missing tags")
    if max_delta_e is not None and max_delta_e >= MAX_DELTA_E:
        reasons.append(f"colour delta_e {max_delta_e:.2f} >= {MAX_DELTA_E}")
    if ssim is None or ssim < MIN_SSIM:
        reasons.append(f"non-text ssim {ssim if ssim is not None else 0:.3f} < {MIN_SSIM}")
    return {"pass": not reasons, "reasons": reasons}


def score(tokens, geometry, colour, ssim):
    """0..100: the mean of the token, geometry, colour and SSIM rates. No token result counts as 0;
    a board without fills leaves colour out."""
    rates = [tokens or 0.0, geometry, ssim or 0.0] + ([colour] if colour is not None else [])
    return 100.0 * sum(rates) / len(rates)
