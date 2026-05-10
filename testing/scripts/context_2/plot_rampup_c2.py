#!/usr/bin/env python3
"""
Context 2 ramp-up — two figures:

  Figure 1: Latency vs Throughput (6 lines: scenario A + B × 1/2/4 CPUs)
  Figure 2: APISIX CPU% and RAM vs Throughput (same 6 lines, 2 subplots)

Scenario A = real devices, Scenario B = device mockup.
Line style: solid = A, dashed = B. Colour = CPU count (same palette both figures).

CONFIG (top of file):
  SAMPLE_STEP — keep every Nth ramp step  (5 → every 50 RPS with STEP_RPS=10)
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

# Colour = CPU count, style = scenario
COLORS    = {"1 CPU": "#f43f5e", "2 CPUs": "#f97316", "4 CPUs": "#3b82f6"}
MARKERS   = {"1 CPU": "s",       "2 CPUs": "^",        "4 CPUs": "o"}
LINESTYLE = {"A": "-", "B": "--"}

LINEWIDTH   = 2.2
MARKER_SIZE = 8

# | ================= Palette ================= |

BG_COLOR    = "#ffffff"
PANEL_COLOR = "#f7f8fc"
GRID_COLOR  = "#e2e5ef"
TEXT_COLOR  = "#1a1d2e"
SPINE_COLOR = "#d1d5e0"

# | ================= Helpers ================= |

def cpu_key(label):
    """Extract CPU-count string from label e.g. 'A — 2 CPUs' → '2 CPUs'."""
    return label.split("— ")[1]

def scenario_key(label):
    """Extract scenario letter from label e.g. 'A — 2 CPUs' → 'A'."""
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
    """Return DataFrame with columns: timestamp, cpu_pct, ram_mib for apisix container.

    The docker stats CSV has 11 data columns but the header only declares 9 —
    net_io and block_io are each split into two comma-separated values (in/out).
    We supply explicit column names to handle this correctly.
    """
    COL_NAMES = [
        "timestamp", "container", "cpu_pct",
        "mem_used", "mem_limit", "mem_pct",
        "net_in", "net_out",
        "block_in", "block_out",
        "pids",
    ]
    df = pd.read_csv(path, header=0, names=COL_NAMES, skiprows=1)
    apisix = df[df["container"] == "apisix"].copy()
    apisix["cpu_pct"] = apisix["cpu_pct"].str.replace("%", "", regex=False).astype(float)
    apisix["ram_mib"] = apisix["mem_used"].apply(parse_mem_mib)
    return apisix[["timestamp", "cpu_pct", "ram_mib"]].reset_index(drop=True)

def process_latency(path):
    """
    Returns a DataFrame with columns: step, achieved_rps, p50,
    t_start (first timestamp of step), t_end (last timestamp of step).
    Already filtered and step-sampled.
    """
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
    # Keep every SAMPLE_STEP-th step
    binned = binned.iloc[SAMPLE_STEP - 1::SAMPLE_STEP].reset_index(drop=True)
    return binned

def match_stats(binned, stats_df, label=""):
    """
    For each step in binned, match stats rows by timestamp.

    Three-tier strategy:
      1. Exact window [t_start, t_end]
      2. Buffered window ± BUFFER_S seconds (handles clock drift between nuc1/nuc4)
      3. Nearest-neighbour on step midpoint (last resort)

    Prints timestamp ranges to help diagnose mismatches.
    """
    BUFFER_S = 15   # seconds of tolerance — covers typical nuc1/nuc4 clock drift

    if stats_df.empty:
        print(f"    [{label}] ERROR: stats DataFrame is empty — check CSV column count")
        binned = binned.copy()
        binned["cpu_pct"] = np.nan
        binned["ram_mib"] = np.nan
        return binned

    lat_min  = binned["t_start"].min()
    lat_max  = binned["t_end"].max()
    stat_min = stats_df["timestamp"].min()
    stat_max = stats_df["timestamp"].max()
    print(f"    [{label}] Latency  timestamps: {lat_min:.0f} — {lat_max:.0f}")
    print(f"    [{label}] Stats    timestamps: {stat_min:.0f} — {stat_max:.0f}")
    if stat_max < lat_min or stat_min > lat_max:
        print(f"    [{label}] WARNING: no overlap — using nearest-neighbour for all steps")

    cpu_vals, ram_vals = [], []
    for _, row in binned.iterrows():
        # 1. Exact window
        mask   = (stats_df["timestamp"] >= row["t_start"]) & \
                 (stats_df["timestamp"] <= row["t_end"])
        window = stats_df[mask]

        # 2. Buffered window
        if window.empty:
            mask   = (stats_df["timestamp"] >= row["t_start"] - BUFFER_S) & \
                     (stats_df["timestamp"] <= row["t_end"]   + BUFFER_S)
            window = stats_df[mask]

        # 3. Nearest-neighbour on midpoint
        if window.empty:
            mid = (row["t_start"] + row["t_end"]) / 2
            window = stats_df.iloc[[(stats_df["timestamp"] - mid).abs().idxmin()]]

        cpu_vals.append(window["cpu_pct"].median())
        ram_vals.append(window["ram_mib"].median())

    binned = binned.copy()
    binned["cpu_pct"] = cpu_vals
    binned["ram_mib"] = ram_vals
    return binned

def apply_connect_by(binned):
    """Sort and clean points for line connection based on CONNECT_BY setting."""
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

# | ================= Load all series ================= |

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
        print(f"  [WARN] {stats_path} not found — stats will be missing for {label}")
        binned["cpu_pct"] = np.nan
        binned["ram_mib"] = np.nan

    binned = apply_connect_by(binned)
    series[label] = binned

    print(f"  {label}: {len(binned)} pts | "
          f"peak={binned['achieved_rps'].max():.0f} req/s | "
          f"p50 max={binned['p50'].max():.0f} ms | "
          f"CPU avg={binned['cpu_pct'].mean():.1f}% | "
          f"RAM avg={binned['ram_mib'].mean():.0f} MiB")

# | ================= Shared axis styling ================= |

def style_ax(ax):
    ax.yaxis.grid(True, color=GRID_COLOR, linewidth=0.9, linestyle="-",  alpha=0.9)
    ax.xaxis.grid(True, color=GRID_COLOR, linewidth=0.6, linestyle="--", alpha=0.5)
    ax.set_axisbelow(True)
    for spine in ax.spines.values():
        spine.set_edgecolor(SPINE_COLOR); spine.set_linewidth(0.8)
    ax.tick_params(colors=TEXT_COLOR, labelsize=10)
    ax.set_xlim(left=0)
    ax.set_ylim(bottom=0)

def plot_series(ax, y_col, series):
    for label, df in series.items():
        if df[y_col].isna().all():
            continue
        color  = COLORS[cpu_key(label)]
        marker = MARKERS[cpu_key(label)]
        ls     = LINESTYLE[scenario_key(label)]
        ax.plot(df["achieved_rps"], df[y_col],
                color=color, linewidth=LINEWIDTH, linestyle=ls,
                marker=marker, markersize=MARKER_SIZE,
                markerfacecolor=color, markeredgecolor="white",
                markeredgewidth=1.3, zorder=3)

# | ================= Shared legend handles ================= |

def make_legend_handles():
    handles = []
    # CPU count colours
    for cpu, color in COLORS.items():
        handles.append(mlines.Line2D([], [], color=color, linewidth=2,
                                     label=cpu, marker=MARKERS[cpu],
                                     markersize=7, markerfacecolor=color,
                                     markeredgecolor="white", markeredgewidth=1))
    # Scenario line styles
    handles.append(mlines.Line2D([], [], color="gray", linewidth=2,
                                  linestyle="-",  label="Scenario A (real devices)"))
    handles.append(mlines.Line2D([], [], color="gray", linewidth=2,
                                  linestyle="--", label="Scenario B (mockup)"))
    return handles

legend_kwargs = dict(
    frameon=True, framealpha=0.9,
    facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
    labelcolor=TEXT_COLOR, fontsize=10,
)

# | ================= Figure 1: Latency vs Throughput ================= |

fig1, ax1 = plt.subplots(figsize=(13, 7))
fig1.patch.set_facecolor(BG_COLOR)
ax1.set_facecolor(PANEL_COLOR)

plot_series(ax1, "p50", series)
style_ax(ax1)

ax1.set_xlabel("Achieved Throughput (req/s)", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax1.set_ylabel("Median Latency p50 (ms)",     fontsize=13, color=TEXT_COLOR, labelpad=10)
ax1.set_title("Context 2 — Latency vs Throughput",
              fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16)
ax1.legend(handles=make_legend_handles(), loc="upper left", **legend_kwargs)

plt.tight_layout(pad=2.0)
out1 = RESULTS_DIR / f"{round(time.time())}_c2_latency.pdf"
plt.savefig(out1, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nFigure 1 saved → {out1}")

# | ================= Figure 2: CPU% and RAM vs Throughput ================= |

fig2, (ax_cpu, ax_ram) = plt.subplots(1, 2, figsize=(16, 7), sharey=False)
fig2.patch.set_facecolor(BG_COLOR)

for ax in (ax_cpu, ax_ram):
    ax.set_facecolor(PANEL_COLOR)

plot_series(ax_cpu, "cpu_pct", series)
style_ax(ax_cpu)
ax_cpu.set_xlabel("Achieved Throughput (req/s)", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax_cpu.set_ylabel("APISIX CPU %",                fontsize=13, color=TEXT_COLOR, labelpad=10)
ax_cpu.set_title("CPU Usage vs Throughput",
                 fontsize=13, fontweight="bold", color=TEXT_COLOR, pad=12)

plot_series(ax_ram, "ram_mib", series)
style_ax(ax_ram)
ax_ram.set_xlabel("Achieved Throughput (req/s)", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax_ram.set_ylabel("APISIX RAM (MiB)",            fontsize=13, color=TEXT_COLOR, labelpad=10)
ax_ram.set_title("RAM Usage vs Throughput",
                 fontsize=13, fontweight="bold", color=TEXT_COLOR, pad=12)

# Shared legend centred below both subplots
fig2.legend(handles=make_legend_handles(),
            loc="lower center", ncol=5,
            bbox_to_anchor=(0.5, -0.08),
            **legend_kwargs)

fig2.suptitle("Context 2 — APISIX Resource Usage vs Throughput",
              fontsize=15, fontweight="bold", color=TEXT_COLOR, y=1.01)

plt.tight_layout(pad=2.0)
out2 = RESULTS_DIR / f"{round(time.time())}_c2_resources.pdf"
plt.savefig(out2, bbox_inches="tight", facecolor=BG_COLOR)
print(f"Figure 2 saved → {out2}")

plt.show()