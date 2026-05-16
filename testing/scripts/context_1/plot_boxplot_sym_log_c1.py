#!/usr/bin/env python3
"""
Box plot of raw latencies across Context 1 scenarios (A-E).
Right y-axis shows APISIX CPU% and RAM (MiB) per scenario from docker stats.
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.ticker as ticker
import numpy as np
import time
from pathlib import Path

# | ================= Config ================= |

RESULTS_DIR = Path("./all_results")
OUTPUTS_DIR = Path("./outputs")

SCENARIOS = {
    "C1SA": "Scenario A\nDirect",
    "C1SB": "Scenario B\nGW Forward",
    "C1SC": "Scenario C\nGW Plugin",
    "C1SD": "Scenario D\nGW Plugin Req",
    "C1SE": "Scenario E\nGW Full Stack",
}

SUCCESS_ONLY     = True
LINEAR_THRESHOLD = 80   # ms

# | ================= Palette ================= |

BG_COLOR     = "#ffffff"
PANEL_COLOR  = "#f7f8fc"
GRID_COLOR   = "#e2e5ef"
TEXT_COLOR   = "#1a1d2e"
ACCENT_COLOR = "#6b7280"
SPINE_COLOR  = "#d1d5e0"

HTTP_FILL   = "#3b82f6"; HTTP_EDGE   = "#1d4ed8"; HTTP_MEDIAN  = "#1e3a8a"
MQTT_FILL   = "#f43f5e"; MQTT_EDGE   = "#be123c"; MQTT_MEDIAN  = "#881337"

CPU_COLOR   = "#16a34a"   # green — distinct from blue/red
RAM_COLOR   = "#9333ea"   # purple

BOX_ALPHA   = 0.55
FLIER_ALPHA = 0.20
LINEWIDTH   = 1.3

# | ================= Load latency data ================= |

def load_latency(key):
    path = RESULTS_DIR / f"{key}_raw_latencies.csv"
    if not path.exists():
        print(f"  [WARN] {path} not found — skipping")
        return pd.DataFrame()
    df = pd.read_csv(path)
    if SUCCESS_ONLY:
        df = df[df["success"] == True]
    return df

print("Loading latency data...")
latency_data = {}
all_values   = []

for key in SCENARIOS:
    df = load_latency(key)
    if df.empty:
        latency_data[key] = {"http": [], "mqtt": []}
        continue
    http_vals = df.loc[df["name"].str.contains("http", case=False, na=False),
                       "response_time_ms"].dropna().tolist()
    mqtt_vals = df.loc[df["name"].str.contains("mqtt", case=False, na=False),
                       "response_time_ms"].dropna().tolist()
    latency_data[key] = {"http": http_vals, "mqtt": mqtt_vals}
    all_values.extend(http_vals + mqtt_vals)
    print(f"  {key}: {len(http_vals):,} HTTP  |  {len(mqtt_vals):,} MQTT")

y_max = float(np.percentile(all_values, 100)) * 1.1 if all_values else 2000

# | ================= Load docker stats ================= |

def load_docker_stats(key):
    """Returns (cpu_median_pct, ram_median_mib) for the apisix container."""
    path = RESULTS_DIR / f"{key}_docker_stats.csv"
    if not path.exists():
        return None, None
    df = pd.read_csv(path)
    apisix = df[df["container"] == "apisix"].copy()
    if apisix.empty:
        return None, None

    # Strip % from cpu, parse MiB from mem_used
    apisix["cpu"] = apisix["cpu_pct_raw"].str.replace("%", "", regex=False).astype(float)

    def parse_mem(s):
        s = str(s).strip()
        val = float(''.join(c for c in s if c.isdigit() or c == '.'))
        if "GiB" in s: return val * 1024
        if "MiB" in s: return val
        if "kB"  in s: return val / 1024
        return val

    apisix["ram_mib"] = apisix["mem_used"].apply(parse_mem)
    return apisix["cpu"].median(), apisix["ram_mib"].median()

print("\nLoading docker stats...")
docker_stats = {}
for key in SCENARIOS:
    cpu, ram = load_docker_stats(key)
    docker_stats[key] = {"cpu": cpu, "ram": ram}
    if cpu is not None:
        print(f"  {key}: CPU={cpu/100:.2f} cores  RAM={ram:.0f} MiB")
    else:
        print(f"  {key}: no docker stats (expected for scenario A)")

# | ================= Build box positions ================= |

positions        = []
box_data         = []
fill_colors      = []
edge_colors      = []
median_colors    = []
scenario_centers = []

pos = 1
for key in SCENARIOS:
    scenario_centers.append((pos + pos + 1) / 2)
    box_data.append(latency_data[key]["http"] if latency_data[key]["http"] else [0])
    box_data.append(latency_data[key]["mqtt"] if latency_data[key]["mqtt"] else [0])
    positions.extend([pos, pos + 1])
    fill_colors.extend([HTTP_FILL, MQTT_FILL])
    edge_colors.extend([HTTP_EDGE, MQTT_EDGE])
    median_colors.extend([HTTP_MEDIAN, MQTT_MEDIAN])
    pos += 3

# | ================= Plot ================= |

fig, ax = plt.subplots(figsize=(16, 9))
fig.patch.set_facecolor(BG_COLOR)
ax.set_facecolor(PANEL_COLOR)

bp = ax.boxplot(
    box_data, positions=positions, widths=0.62, patch_artist=True,
    showfliers=True,
    flierprops=dict(marker="o", markersize=2.5, alpha=FLIER_ALPHA, linestyle="none"),
    medianprops=dict(linewidth=2.2),
    whiskerprops=dict(linewidth=LINEWIDTH, linestyle="--", color=SPINE_COLOR),
    capprops=dict(linewidth=LINEWIDTH + 0.3, color=SPINE_COLOR),
    boxprops=dict(linewidth=LINEWIDTH),
)

for patch, fc, ec in zip(bp["boxes"], fill_colors, edge_colors):
    patch.set_facecolor(fc); patch.set_alpha(BOX_ALPHA); patch.set_edgecolor(ec)
for med, mc in zip(bp["medians"], median_colors):
    med.set_color(mc); med.set_linewidth(2.2)
for flier, fc in zip(bp["fliers"], fill_colors):
    flier.set_markerfacecolor(fc); flier.set_markeredgecolor(fc)

# | ================= Symlog scale (left axis) ================= |

ax.set_yscale("symlog", linthresh=LINEAR_THRESHOLD, linscale=2.5)
ax.set_ylim(bottom=0, top=y_max)

linear_ticks = list(range(0, LINEAR_THRESHOLD + 1, 10))
log_ticks    = [t for t in [100, 200, 500, 1000, 2000, 5000, 10000] if t <= y_max]
all_ticks    = sorted(set(linear_ticks + log_ticks))
ax.set_yticks(all_ticks)
ax.set_yticklabels([str(t) for t in all_ticks], fontsize=9.5)

ax.axhspan(0, LINEAR_THRESHOLD, color="#3b82f6", alpha=0.04, zorder=0)
ax.axhline(LINEAR_THRESHOLD, color="#3b82f6", linewidth=0.8,
           linestyle=":", alpha=0.5, zorder=1)

# | ================= Right axis: CPU% ================= |

ax2 = ax.twinx()
ax2.set_facecolor("none")

# Collect CPU and RAM values aligned to scenario centers
cpu_x   = [scenario_centers[i] for i, k in enumerate(SCENARIOS)
            if docker_stats[k]["cpu"] is not None]
cpu_y   = [docker_stats[k]["cpu"] / 100 for k in SCENARIOS
            if docker_stats[k]["cpu"] is not None]
ram_y   = [docker_stats[k]["ram"] for k in SCENARIOS
            if docker_stats[k]["ram"] is not None]

if cpu_x:
    # CPU line on right axis
    ax2.plot(cpu_x, cpu_y,
             color=CPU_COLOR, linewidth=1.8, linestyle="--",
             marker="D", markersize=7,
             markerfacecolor=CPU_COLOR, markeredgecolor="white",
             markeredgewidth=1.2,
             label="APISIX CPU %", zorder=5)

    # RAM as T-shaped vertical bars
    RAM_MAX_FRAC = 0.5
    bar_scale = max(cpu_y) * 2.2 * RAM_MAX_FRAC / max(ram_y)
    cap_width = 0.08

    for x, ram in zip(cpu_x, ram_y):
        bar_top = ram * bar_scale
        ax2.plot([x, x], [0, bar_top],
                 color=RAM_COLOR, linewidth=1.2, alpha=0.75, zorder=6)
        ax2.plot([x - cap_width, x + cap_width], [bar_top, bar_top],
                 color=RAM_COLOR, linewidth=1.5, alpha=0.85, zorder=6)
        ax2.annotate(f"{ram:.0f} MiB",
                     xy=(x, bar_top), xytext=(0, 5),
                     textcoords="offset points",
                     ha="center", va="bottom",
                     fontsize=7.5, color=RAM_COLOR,
                     fontweight="500", rotation=90)

    ax2.set_ylabel("APISIX CPU cores utilised", fontsize=12, color=CPU_COLOR, labelpad=10)
    ax2.tick_params(axis="y", colors=CPU_COLOR, labelsize=9.5)
    ax2.set_ylim(bottom=0, top=max(cpu_y) * 2.2)
    ax2.spines["right"].set_edgecolor(CPU_COLOR)
    ax2.spines["right"].set_linewidth(0.8)
    for s in ["top", "left", "bottom"]:
        ax2.spines[s].set_visible(False)

# | ================= Styling ================= |

ax.yaxis.grid(True, color=GRID_COLOR, linewidth=0.9, linestyle="-", alpha=0.9)
ax.set_axisbelow(True)
ax.xaxis.grid(False)

for i in range(1, len(SCENARIOS)):
    sep_x = positions[i * 2] - 1
    ax.axvline(sep_x, color=GRID_COLOR, linewidth=1.2, zorder=0)

ax.set_xlim(0.2, max(positions) + 0.8)
ax.set_xticks(scenario_centers)
ax.set_xticklabels([SCENARIOS[k] for k in SCENARIOS],
                   fontsize=11.5, color=TEXT_COLOR, fontweight="600")
ax.tick_params(axis="x", which="both", bottom=False, colors=TEXT_COLOR)
ax.tick_params(axis="y", colors=TEXT_COLOR)

for spine in ax.spines.values():
    spine.set_edgecolor(SPINE_COLOR); spine.set_linewidth(0.8)

ax.set_ylabel("Latency (ms)", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax.set_xlabel("Scenario", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax.set_title("Context 1 — Latency Distribution per Scenario and Protocol",
             fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16)

# | ================= Legend ================= |

http_patch = mpatches.Patch(facecolor=HTTP_FILL, alpha=BOX_ALPHA,
                             edgecolor=HTTP_EDGE, label="HTTP latency")
mqtt_patch = mpatches.Patch(facecolor=MQTT_FILL, alpha=BOX_ALPHA,
                             edgecolor=MQTT_FILL, label="MQTT latency")
cpu_line   = plt.Line2D([0], [0], color=CPU_COLOR, linewidth=1.8,
                         linestyle="--", marker="D", markersize=7,
                         markerfacecolor=CPU_COLOR, markeredgecolor="white",
                         label="APISIX CPU cores utilised (median)")
ram_patch  = mpatches.Patch(facecolor=RAM_COLOR, alpha=0.8,
                             edgecolor=RAM_COLOR, label="APISIX RAM peak usage (annotated)")

fig.legend(
    handles=[http_patch, mqtt_patch, cpu_line, ram_patch],
    loc="lower center",
    bbox_to_anchor=(0.5, -0.04),
    ncol=4,
    frameon=True, framealpha=0.4,
    facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
    labelcolor=TEXT_COLOR, fontsize=11,
    title="Legend", title_fontsize=10,
)

plt.tight_layout(pad=2.0)
plt.subplots_adjust(bottom=0.12)

# Scale labels just left of the y-axis
LABEL_X = 0.004

ax.annotate("↓ linear", xy=(LABEL_X, LINEAR_THRESHOLD),
            xycoords=("axes fraction", "data"),
            xytext=(0, -4), textcoords="offset points",
            ha="left", va="top",
            fontsize=8, color="#3b82f6", alpha=0.8, style="italic",
            annotation_clip=False)

ax.annotate("↑ log", xy=(LABEL_X, LINEAR_THRESHOLD),
            xycoords=("axes fraction", "data"),
            xytext=(0, 4), textcoords="offset points",
            ha="left", va="bottom",
            fontsize=8, color="#3b82f6", alpha=0.8, style="italic",
            annotation_clip=False)

OUTPUTS_DIR.mkdir(exist_ok=True)
out_path = OUTPUTS_DIR / f"{round(time.time())}_c1_boxplot.pdf"
plt.savefig(out_path, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nSaved → {out_path}")
plt.show()