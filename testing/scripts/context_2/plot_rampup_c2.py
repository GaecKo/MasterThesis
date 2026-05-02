#!/usr/bin/env python3
"""
Throughput vs Latency — Context 2 ramp-up.
One point per RPS step (median p50 within each step window).
Files: ./all_results/cpu1.csv, cpu2.csv, cpu4.csv
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
from pathlib import Path

# | ================= Config ================= |

RESULTS_DIR  = Path("./all_results")
STEP_RPS     = 10      # RPS increment used in the ramp — bins raw data into steps
MIN_RPS      = 5.0     # discard warmup/transition ticks below this

FILES = {
    "1 CPU":  "cpu1.csv",
    "2 CPUs": "cpu2.csv",
    "4 CPUs": "cpu4.csv",
}

# | ================= Palette ================= |

BG_COLOR     = "#ffffff"
PANEL_COLOR  = "#f7f8fc"
GRID_COLOR   = "#e2e5ef"
TEXT_COLOR   = "#1a1d2e"
SPINE_COLOR  = "#d1d5e0"

COLORS = {
    "1 CPU":  "#f43f5e",
    "2 CPUs": "#f97316",
    "4 CPUs": "#3b82f6",
}
MARKERS = {
    "1 CPU":  "s",
    "2 CPUs": "^",
    "4 CPUs": "o",
}

LINEWIDTH   = 2.0
MARKER_SIZE = 7

# | ================= Load and bin ================= |

print("Loading data...")
series = {}

for label, filename in FILES.items():
    path = RESULTS_DIR / filename
    if not path.exists():
        print(f"  [WARN] {path} not found — skipping")
        continue

    df = pd.read_csv(path)

    # Keep only Aggregated rows
    agg = df[df["Name"] == "Aggregated"].copy()
    agg["rps"] = pd.to_numeric(agg["Requests/s"], errors="coerce")
    agg["p50"] = pd.to_numeric(agg["50%"],         errors="coerce")
    agg = agg.dropna(subset=["rps", "p50"])
    agg = agg[(agg["rps"] >= MIN_RPS) & (agg["p50"] > 0)]

    # Bin into steps: round RPS to nearest STEP_RPS increment
    # Each step holds STEP_DURATION_S seconds of 2s windows → ~15 points per bin
    agg["rps_bin"] = (agg["rps"] / STEP_RPS).round() * STEP_RPS

    # One point per bin: median achieved RPS and median p50 latency
    binned = (agg.groupby("rps_bin")
                 .agg(achieved_rps=("rps", "median"),
                      p50=("p50", "median"))
                 .reset_index()
                 .sort_values("achieved_rps"))

    # Drop the very last point if latency exploded (timeout artifacts)
    # Keep it if you want to show full saturation
    series[label] = binned
    print(f"  {label}: {len(binned)} steps, "
          f"max RPS={binned['achieved_rps'].max():.0f}, "
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
            markeredgewidth=1.2,
            label=label, zorder=3)

# | ================= Styling ================= |

ax.yaxis.grid(True, color=GRID_COLOR, linewidth=0.9, linestyle="-", alpha=0.9)
ax.xaxis.grid(True, color=GRID_COLOR, linewidth=0.6, linestyle="--", alpha=0.5)
ax.set_axisbelow(True)

for spine in ax.spines.values():
    spine.set_edgecolor(SPINE_COLOR)
    spine.set_linewidth(0.8)

ax.tick_params(axis="both", colors=TEXT_COLOR, labelsize=10)
ax.set_xlim(left=0)
ax.set_ylim(bottom=0)

ax.set_xlabel("Achieved Throughput (req/s)", fontsize=13,
              color=TEXT_COLOR, labelpad=10)
ax.set_ylabel("Median Latency p50 (ms)", fontsize=13,
              color=TEXT_COLOR, labelpad=10)
ax.set_title("Context 2 — Latency vs Throughput",
             fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16)

legend = ax.legend(
    loc="upper left", frameon=True, framealpha=0.9,
    facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
    labelcolor=TEXT_COLOR, fontsize=12,
)

plt.tight_layout(pad=2.0)

out_path = RESULTS_DIR / "c2_rampup.png"
plt.savefig(out_path, dpi=180, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nSaved → {out_path}")
plt.show()