#!/usr/bin/env python3
"""
Throughput vs Latency — Context 2 ramp-up.

CONFIG:
  SAMPLE_STEP  — keep only every Nth RPS step (e.g. 5 keeps steps 50,100,150...)
  CONNECT_BY   — "latency" : sort points low-to-high latency before connecting
               — "time"    : connect in chronological (step) order
"""

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import time 
from pathlib import Path

# | ================= Config ================= |

RESULTS_DIR = Path("./all_results")
MIN_RPS     = 5.0

# Keep one point every SAMPLE_STEP ramp steps.
# With STEP_RPS=10 in your run: SAMPLE_STEP=5 → points at 50,100,150,...
SAMPLE_STEP = 5

# How to connect points:
#   "latency"  → sort by p50 ascending (smooth natural curve, good for presentation)
#   "time"     → connect in step order  (shows collapse going backward)
CONNECT_BY = "latency"

FILES = {
    "1 CPU":  "a_cpu1.csv",
    "2 CPUs": "a_cpu2.csv",
    "4 CPUs": "a_cpu4.csv",
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

# | ================= Load and process ================= |

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
    agg = (agg.dropna(subset=["rps", "p50"])
              .sort_values("Timestamp")
              .reset_index(drop=True))
    agg = agg[(agg["rps"] >= MIN_RPS) & (agg["p50"] > 0)]

    # Detect step boundaries from User Count changes
    agg["step"] = (agg["User Count"] != agg["User Count"].shift()).cumsum()

    # Per step: drop first 3 rows (transition), take median
    rows = []
    for step_id, grp in agg.groupby("step"):
        grp = grp.iloc[3:]
        if len(grp) < 2:
            continue
        rows.append({
            "step":         step_id,
            "achieved_rps": grp["rps"].median(),
            "p50":          grp["p50"].median(),
        })

    binned = pd.DataFrame(rows).sort_values("step").reset_index(drop=True)

    # Keep only every SAMPLE_STEP-th step
    binned = binned.iloc[SAMPLE_STEP - 1::SAMPLE_STEP].reset_index(drop=True)

    # Order for line connection
    if CONNECT_BY == "latency":
        binned = binned.sort_values(["p50", "achieved_rps"]).reset_index(drop=True)

        # Remove points that go backwards in RPS before the peak —
        # these are noise in the flat zone that cause zigzag.
        # Strategy: keep only points where RPS >= all previous RPS values,
        # until we reach the peak RPS. After the peak, keep everything
        # (that's the real collapse/saturation zone we want to show).
        peak_idx  = binned["achieved_rps"].idxmax()
        pre_peak  = binned.iloc[:peak_idx + 1]
        post_peak = binned.iloc[peak_idx + 1:]

        # Forward pass: keep a point only if its RPS >= running max so far
        keep = []
        running_max = 0
        for _, row in pre_peak.iterrows():
            if row["achieved_rps"] >= running_max:
                running_max = row["achieved_rps"]
                keep.append(row)

        pre_clean = pd.DataFrame(keep)
        binned = pd.concat([pre_clean, post_peak]).reset_index(drop=True)
    # "time" → already in step order

    series[label] = binned
    print(f"  {label}: {len(binned)} points | "
          f"peak RPS={binned['achieved_rps'].max():.0f} | "
          f"max p50={binned['p50'].max():.0f} ms  "
          f"[connect_by={CONNECT_BY}]")

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

connect_label = "sorted by latency" if CONNECT_BY == "latency" else "chronological order"
ax.set_title(f"Context 2 — Latency vs Throughput  ({connect_label})",
             fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16)

ax.legend(loc="upper left", frameon=True, framealpha=0.9,
          facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
          labelcolor=TEXT_COLOR, fontsize=12)

plt.tight_layout(pad=2.0)

out_path = RESULTS_DIR / f"{time.time()}_c2_rampup.png"
plt.savefig(out_path, dpi=180, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nSaved → {out_path}")
plt.show()