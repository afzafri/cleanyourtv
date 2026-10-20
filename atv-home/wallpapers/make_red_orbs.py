"""Generates wallpapers/red-orbs.jpg: soft blurred red orbs on black, 1280x720 (the TV's resolution)."""
import numpy as np
from PIL import Image

W, H = 1280, 720
rng = np.random.default_rng(7)
y, x = np.mgrid[0:H, 0:W].astype(np.float32)
img = np.zeros((H, W, 3), np.float32)

# (centre x, centre y, radius, colour, strength). The top-left stays dark so the clock reads cleanly.
orbs = [
    (1030, 190, 260, (0.95, 0.08, 0.10), 1.00),   # big crimson, top right
    (700, 560, 210, (0.80, 0.03, 0.22), 0.75),    # deep magenta-red, lower middle
    (170, 660, 190, (0.90, 0.20, 0.06), 0.50),    # warm red-orange, bottom left
    (1220, 640, 170, (0.70, 0.02, 0.08), 0.60),   # dark wine, bottom right
    (470, 300, 120, (0.95, 0.22, 0.28), 0.30),    # faint pink haze, middle
]
for cx, cy, r, col, s in orbs:
    g = np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * (r / 2.2) ** 2)) * s
    img += g[..., None] * np.array(col, np.float32)

img = 1 - np.exp(-img * 1.4)                     # soft tone-map so overlaps don't clip
img += rng.normal(0, 1.2 / 255, img.shape)        # fine grain to stop gradient banding
Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(
    "wallpapers/red-orbs.jpg", quality=95, subsampling=0)
print("wrote wallpapers/red-orbs.jpg")
