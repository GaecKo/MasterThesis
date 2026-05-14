#!/usr/bin/env python3
"""
Context 2 — Latency vs Throughput with CPU% and RAM overlaid.

6 lines: Scenario A (real devices) + Scenario B (mockup) × 1/2/4 CPUs.
  - Left Y axis : latency p50 (ms)  — bold lines
  - Right Y axis: APISIX CPU %      — faint lines, same colour/style
  - RAM MiB     : annotated at peak RPS of each series

Line style: solid = A, dashed = B.  Colour = CPU count.

CONFIG:
  SAMPLE_STEP — keep every Nth ramp step (5 → every 50 RPS with STEP_RPS=10)
  CONNECT_BY  — "latency" | "time"
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.lines as mlines
import numpy as np
import time
from pathlib import Path

# | ================= Config ================= |

RESULTS_DIR = Path("./all_results")
OUTPUTS_DIR = Path("./outputs")
MIN_RPS     = 5.0
SAMPLE_STEP = 5
CONNECT_BY  = "latency"

FILES = {
    "A — 1 CPU":  {"latency": "a_cpu1.csv", "stats": "a_cpu1_stats.csv"},
    "A — 2 CPUs": {"latency": "a_cpu2.csv", "stats": "a_cpu2_stats.csv"},
    "A — 4 CPUs": {"latency": "a_cpu4.csv", "stats": "a_cpu4_stats.csv"},
    "B — 1 CPU":  {"latency": "b_cpu1.csv", "stats": "b_cpu1_stats.csv"},
    "B — 2 CPUs": {"latency": "b_cpu2.csv", "stats": "b_cpu2_stats.csv"},
    "B — 4 CPUs": {"latency": "b_cpu4.csv", "stats": "b_cpu4_stats.csv"},
}

COLORS    = {"1 CPU": "#f43f5e", "2 CPUs": "#f97316", "4 CPUs": "#3b82f6"}
MARKERS   = {"1 CPU": "s",       "2 CPUs": "^",        "4 CPUs": "o"}
LINESTYLE = {"A": "-", "B": "--"}

LINEWIDTH   = 2.2
MARKER_SIZE = 8

# | ================= Palette ================= |

BG_COLOR     = "#ffffff"
PANEL_COLOR  = "#f7f8fc"
GRID_COLOR   = "#e2e5ef"
TEXT_COLOR   = "#1a1d2e"
SPINE_COLOR  = "#d1d5e0"
ACCENT_COLOR = "#6b7280"

# | ================= Helpers ================= |

def cpu_key(label):
    return label.split("— ")[1]

def scenario_key(label):
    return label.split(" —")[0]

def parse_mem_mib(s):
    s = str(s).strip()
    val = float("".join(c for c in s if c.isdigit() or c == "."))
    if "GiB" in s: return val * 1024
    if "MiB" in s: return val
    if "kB"  in s: return val / 1024
    if "MB"  in s: return val * 0.9537
    return val

def load_stats(path):
    COL_NAMES = [
        "timestamp", "container", "cpu_pct",
        "mem_used", "mem_limit", "mem_pct",
        "net_in", "net_out", "block_in", "block_out", "pids",
    ]
    df = pd.read_csv(path, header=0, names=COL_NAMES, skiprows=1)
    apisix = df[df["container"] == "apisix"].copy()
    apisix["cpu_pct"] = apisix["cpu_pct"].str.replace("%", "", regex=False).astype(float)
    apisix["ram_mib"] = apisix["mem_used"].apply(parse_mem_mib)
    return apisix[["timestamp", "cpu_pct", "ram_mib"]].reset_index(drop=True)

def process_latency(path):
    df = pd.read_csv(path)
    agg = df[df["Name"] == "Aggregated"].copy()
    agg["rps"] = pd.to_numeric(agg["Requests/s"], errors="coerce")
    agg["p50"] = pd.to_numeric(agg["50%"],         errors="coerce")
    agg = (agg.dropna(subset=["rps", "p50"])
              .sort_values("Timestamp")
              .reset_index(drop=True))
    agg = agg[(agg["rps"] >= MIN_RPS) & (agg["p50"] > 0)]
    agg["step"] = (agg["User Count"] != agg["User Count"].shift()).cumsum()

    rows = []
    for step_id, grp in agg.groupby("step"):
        grp = grp.iloc[3:]
        if len(grp) < 2:
            continue
        rows.append({
            "step":         step_id,
            "achieved_rps": grp["rps"].median(),
            "p50":          grp["p50"].median(),
            "t_start":      grp["Timestamp"].min(),
            "t_end":        grp["Timestamp"].max(),
        })

    binned = pd.DataFrame(rows).sort_values("step").reset_index(drop=True)
    binned = binned.iloc[SAMPLE_STEP - 1::SAMPLE_STEP].reset_index(drop=True)
    return binned

def match_stats(binned, stats_df, label=""):
    BUFFER_S = 15

    if stats_df.empty:
        print(f"  [{label}] ERROR: stats DataFrame is empty")
        binned = binned.copy()
        binned["cpu_pct"] = np.nan
        binned["ram_mib"] = np.nan
        return binned

    lat_min  = binned["t_start"].min()
    lat_max  = binned["t_end"].max()
    stat_min = stats_df["timestamp"].min()
    stat_max = stats_df["timestamp"].max()
    print(f"  [{label}] Latency {lat_min:.0f}—{lat_max:.0f}  "
          f"Stats {stat_min:.0f}—{stat_max:.0f}")
    if stat_max < lat_min or stat_min > lat_max:
        print(f"  [{label}] WARNING: no timestamp overlap")

    cpu_vals, ram_vals = [], []
    for _, row in binned.iterrows():
        mask   = (stats_df["timestamp"] >= row["t_start"]) & \
                 (stats_df["timestamp"] <= row["t_end"])
        window = stats_df[mask]
        if window.empty:
            mask   = (stats_df["timestamp"] >= row["t_start"] - BUFFER_S) & \
                     (stats_df["timestamp"] <= row["t_end"]   + BUFFER_S)
            window = stats_df[mask]
        if window.empty:
            mid    = (row["t_start"] + row["t_end"]) / 2
            window = stats_df.iloc[[(stats_df["timestamp"] - mid).abs().idxmin()]]
        cpu_vals.append(window["cpu_pct"].median())
        ram_vals.append(window["ram_mib"].median())

    binned = binned.copy()
    binned["cpu_pct"] = cpu_vals
    binned["ram_mib"] = ram_vals
    return binned

def apply_connect_by(binned):
    if CONNECT_BY == "latency":
        binned = binned.sort_values(["p50", "achieved_rps"]).reset_index(drop=True)
        peak_idx  = binned["achieved_rps"].idxmax()
        pre_peak  = binned.iloc[:peak_idx + 1]
        post_peak = binned.iloc[peak_idx + 1:]
        keep, running_max = [], 0
        for _, row in pre_peak.iterrows():
            if row["achieved_rps"] >= running_max:
                running_max = row["achieved_rps"]
                keep.append(row)
        binned = pd.concat([pd.DataFrame(keep), post_peak]).reset_index(drop=True)
    return binned

# | ================= Load ================= |

print("Loading data...")
series = {}

for label, paths in FILES.items():
    lat_path   = RESULTS_DIR / paths["latency"]
    stats_path = RESULTS_DIR / paths["stats"]

    if not lat_path.exists():
        print(f"  [WARN] {lat_path} not found — skipping"); continue

    binned = process_latency(lat_path)

    if stats_path.exists():
        stats_df = load_stats(stats_path)
        binned   = match_stats(binned, stats_df, label=label)
    else:
        print(f"  [WARN] {stats_path} not found")
        binned["cpu_pct"] = np.nan
        binned["ram_mib"] = np.nan

    full_binned = binned.copy()   # all steps with CPU/RAM, before latency filtering
    binned      = apply_connect_by(binned)
    series[label] = {
        "latency": binned,
        "stats":   full_binned.sort_values("achieved_rps").reset_index(drop=True),
    }
    print(f"  {label}: {len(binned)} latency pts | {len(full_binned)} CPU pts | "
          f"peak={binned['achieved_rps'].max():.0f} req/s | "
          f"p50 max={binned['p50'].max():.0f} ms | "
          f"CPU avg={full_binned['cpu_pct'].mean():.1f}% | "
          f"RAM avg={full_binned['ram_mib'].mean():.0f} MiB")

# | ================= Plot ================= |

fig, ax = plt.subplots(figsize=(14, 7))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(PANEL_COLOR)

# Left axis — latency (filtered/monotone series)
for label, entry in series.items():
    df     = entry["latency"]
    color  = COLORS[cpu_key(label)]
    marker = MARKERS[cpu_key(label)]
    ls     = LINESTYLE[scenario_key(label)]
    ax.plot(df["achieved_rps"], df["p50"],
            color=color, linewidth=LINEWIDTH, linestyle=ls,
            marker=marker, markersize=MARKER_SIZE,
            markerfacecolor=color, markeredgecolor="white",
            markeredgewidth=1.3, zorder=3)

ax.yaxis.grid(True, color=GRID_COLOR, linewidth=0.9, linestyle="-",  alpha=0.9)
ax.xaxis.grid(True, color=GRID_COLOR, linewidth=0.6, linestyle="--", alpha=0.5)
ax.set_axisbelow(True)
for spine in ax.spines.values():
    spine.set_edgecolor(SPINE_COLOR); spine.set_linewidth(0.8)
ax.tick_params(colors=TEXT_COLOR, labelsize=10)
ax.set_xlim(left=0)
ax.set_ylim(bottom=0)
ax.set_xlabel("Achieved Throughput (req/s)", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax.set_ylabel("Median Latency p50 (ms)",     fontsize=13, color=TEXT_COLOR, labelpad=10)

# Right axis — CPU%
ax2 = ax.twinx()
ax2.set_facecolor("none")
ax2.set_ylabel("APISIX CPU cores utilised", fontsize=11, color=ACCENT_COLOR, labelpad=10)
ax2.tick_params(axis="y", colors=ACCENT_COLOR, labelsize=9)
ax2.spines["right"].set_edgecolor("#ccc")
ax2.spines["right"].set_linewidth(0.8)
for s in ["top", "left", "bottom"]:
    ax2.spines[s].set_visible(False)

cpu_max_all = max(
    (entry["stats"]["cpu_pct"].max() for entry in series.values()
     if not entry["stats"]["cpu_pct"].isna().all()),
    default=100
) / 100   # convert to cores
ax2.set_ylim(bottom=0, top=cpu_max_all * 1.3)

CPU_SMOOTH = 9   # rolling median window 

for label, entry in series.items():
    df = entry["stats"].copy()
    if df["cpu_pct"].isna().all():
        continue
    color = COLORS[cpu_key(label)]
    ls    = LINESTYLE[scenario_key(label)]
    # Rolling median smoothing — robust against outlier spikes, then divide to cores
    cpu_smooth = df["cpu_pct"].rolling(CPU_SMOOTH, center=True, min_periods=1).median() / 100
    ax2.plot(df["achieved_rps"], cpu_smooth,
             color=color, linewidth=1.0, linestyle=ls,
             alpha=0.35, zorder=2)

    # RAM annotation at peak RPS — y position uses smoothed CPU value
    peak_row = df.loc[df["achieved_rps"].idxmax()]
    peak_cpu_smooth = cpu_smooth.iloc[df["achieved_rps"].idxmax()]
    if not np.isnan(peak_row["ram_mib"]):
        ax2.annotate(
            f"{peak_row['ram_mib']:.0f} MiB",
            xy=(peak_row["achieved_rps"], peak_cpu_smooth),
            xytext=(4, 4), textcoords="offset points",
            fontsize=6.5, color=color, alpha=0.75,
            ha="left", va="bottom",
        )

ax.set_title("Context 2 — Latency vs Throughput",
             fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16)

# | ================= Legend ================= |

cpu_handles = [
    mlines.Line2D([], [], color=COLORS[c], linewidth=2, marker=MARKERS[c],
                  markersize=7, markerfacecolor=COLORS[c],
                  markeredgecolor="white", markeredgewidth=1, label=c)
    for c in ["1 CPU", "2 CPUs", "4 CPUs"]
]
scenario_handles = [
    mlines.Line2D([], [], color="#555", linewidth=2, linestyle="-",
                  label="Scenario A (real devices)"),
    mlines.Line2D([], [], color="#555", linewidth=2, linestyle="--",
                  label="Scenario B (device mockup)"),
]
extra_handles = [
    mlines.Line2D([], [], color="#999", linewidth=1.0, alpha=0.5,
                  label="Faint lines = CPU cores utilised"),
    mlines.Line2D([], [], color="none",
                  label="Labels at peak = RAM (MiB)"),
]

fig.legend(
    handles=cpu_handles + scenario_handles + extra_handles,
    loc="lower center",
    bbox_to_anchor=(0.5, -0.04),
    ncol=3,
    frameon=True, framealpha=0.9,
    facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
    labelcolor=TEXT_COLOR, fontsize=10,
    title="Legend", title_fontsize=9,
)

plt.tight_layout(pad=2.0)
plt.subplots_adjust(bottom=0.18)   # make room below the plot for the legend
out = OUTPUTS_DIR / f"{round(time.time())}_c2_latency_full.pdf"
plt.savefig(out, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nSaved → {out}")
plt.show()