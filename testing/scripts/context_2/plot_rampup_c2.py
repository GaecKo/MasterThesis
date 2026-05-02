#!/usr/bin/env python3
"""
Throughput vs Latency — Context 2 ramp-up.
Bins data into coarse RPS buckets, then sorts points by latency (low to high)
so the line flows naturally from the stable zone up into saturation.
"""

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
from pathlib import Path

# | ================= Config ================= |

RESULTS_DIR = Path("./all_results")
BIN_SIZE    = 30     # aggregate every N req/s — increase for fewer, smoother points
MIN_RPS     = 5.0

FILES = {
    "1 CPU":  "cpu1.csv",
    "2 CPUs": "cpu2.csv",
    "4 CPUs": "cpu4.csv",
}

# | ================= Palette ================= |

BG_COLOR    = "#ffffff"
PANEL_COLOR = "#f7f8fc"
GRID_COLOR  = "#e2e5ef"
TEXT_COLOR  = "#1a1d2e"
SPINE_COLOR = "#d1d5e0"

COLORS  = {"1 CPU": "#f43f5e", "2 CPUs": "#f97316", "4 CPUs": "#3b82f6"}
MARKERS = {"1 CPU": "s",       "2 CPUs": "^",        "4 CPUs": "o"}

LINEWIDTH   = 2.2
MARKER_SIZE = 8

# | ================= Load and bin ================= |

print("Loading data...")
series = {}

for label, filename in FILES.items():
    path = RESULTS_DIR / filename
    if not path.exists():
        print(f"  [WARN] {path} not found — skipping"); continue

    df = pd.read_csv(path)
    agg = df[df["Name"] == "Aggregated"].copy()
    agg["rps"] = pd.to_numeric(agg["Requests/s"], errors="coerce")
    agg["p50"] = pd.to_numeric(agg["50%"],         errors="coerce")
    agg = agg.dropna(subset=["rps", "p50"])
    agg = agg[(agg["rps"] >= MIN_RPS) & (agg["p50"] > 0)]

    # Bin by achieved RPS into coarse buckets
    agg["bin"] = (agg["rps"] / BIN_SIZE).round() * BIN_SIZE
    binned = (agg.groupby("bin")
                 .agg(achieved_rps=("rps", "median"),
                      p50=("p50", "median"))
                 .reset_index(drop=True))

    # Sort by latency ascending — line flows naturally from stable to saturated
    binned = binned.sort_values("p50").reset_index(drop=True)

    series[label] = binned
    print(f"  {label}: {len(binned)} points | "
          f"peak RPS={binned['achieved_rps'].max():.0f} | "
          f"max p50={binned['p50'].max():.0f} ms")

# | ================= Plot ================= |

fig, ax = plt.subplots(figsize=(12, 7))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(PANEL_COLOR)

for label, df in series.items():
    color  = COLORS.get(label,  "#6b7280")
    marker = MARKERS.get(label, "o")

    ax.plot(df["achieved_rps"], df["p50"],
            color=color, linewidth=LINEWIDTH,
            marker=marker, markersize=MARKER_SIZE,
            markerfacecolor=color, markeredgecolor="white",
            markeredgewidth=1.3,
            label=label, zorder=3)

# | ================= Styling ================= |

ax.yaxis.grid(True, color=GRID_COLOR, linewidth=0.9, linestyle="-",  alpha=0.9)
ax.xaxis.grid(True, color=GRID_COLOR, linewidth=0.6, linestyle="--", alpha=0.5)
ax.set_axisbelow(True)

for spine in ax.spines.values():
    spine.set_edgecolor(SPINE_COLOR); spine.set_linewidth(0.8)

ax.tick_params(colors=TEXT_COLOR, labelsize=10)
ax.set_xlim(left=0)
ax.set_ylim(bottom=0)

ax.set_xlabel("Achieved Throughput (req/s)", fontsize=13,
              color=TEXT_COLOR, labelpad=10)
ax.set_ylabel("Median Latency p50 (ms)", fontsize=13,
              color=TEXT_COLOR, labelpad=10)
ax.set_title("Context 2 — Latency vs Throughput (saturation curve)",
             fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16)

ax.legend(loc="upper left", frameon=True, framealpha=0.9,
          facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
          labelcolor=TEXT_COLOR, fontsize=12)

plt.tight_layout(pad=2.0)

out_path = RESULTS_DIR / "c2_rampup.png"
plt.savefig(out_path, dpi=180, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nSaved → {out_path}")
plt.show()