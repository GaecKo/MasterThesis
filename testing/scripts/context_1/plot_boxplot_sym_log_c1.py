#!/usr/bin/env python3
"""
Box plot of raw latencies across Context 1 scenarios (A-E).

Uses a symlog y-axis: linear between 0 and LINEAR_THRESHOLD (where most
data lives, giving it more visual height), then logarithmic above it so
outliers are still visible without dominating the chart.

Usage:  python3 plot_boxplot_c1.py
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.ticker as ticker
import numpy as np
from pathlib import Path

# | ================= Config ================= |

RESULTS_DIR = Path("./all_results")

SCENARIOS = {
    "C1SA": "Scenario A\nDirect",
    "C1SB": "Scenario B\nGW Forward",
    "C1SC": "Scenario C\nGW Plugin",
    "C1SD": "Scenario D\nPlugin Req",
    "C1SE": "Scenario E\nFull Stack",
}

SUCCESS_ONLY = True

# Linear region of symlog: values below this threshold get linear (tall) space.
# Set to roughly the top of your main distribution.
LINEAR_THRESHOLD = 80   # ms

# | ================= Palette ================= |

BG_COLOR     = "#ffffff"
PANEL_COLOR  = "#f7f8fc"
GRID_COLOR   = "#e2e5ef"
TEXT_COLOR   = "#1a1d2e"
ACCENT_COLOR = "#6b7280"
SPINE_COLOR  = "#d1d5e0"

HTTP_FILL    = "#3b82f6"
HTTP_EDGE    = "#1d4ed8"
HTTP_MEDIAN  = "#1e3a8a"

MQTT_FILL    = "#f43f5e"
MQTT_EDGE    = "#be123c"
MQTT_MEDIAN  = "#881337"

BOX_ALPHA    = 0.55
FLIER_ALPHA  = 0.20
LINEWIDTH    = 1.3

# | ================= Load data ================= |

def load_scenario(key):
    path = RESULTS_DIR / f"{key}_raw_latencies.csv"
    if not path.exists():
        print(f"  [WARN] {path} not found — skipping")
        return pd.DataFrame()
    df = pd.read_csv(path)
    if SUCCESS_ONLY:
        df = df[df["success"] == True]
    return df

print("Loading data...")
data = {}
all_values = []

for key in SCENARIOS:
    df = load_scenario(key)
    if df.empty:
        data[key] = {"http": [], "mqtt": []}
        continue
    http_vals = df.loc[df["name"].str.contains("http", case=False, na=False),
                       "response_time_ms"].dropna().tolist()
    mqtt_vals = df.loc[df["name"].str.contains("mqtt", case=False, na=False),
                       "response_time_ms"].dropna().tolist()
    data[key] = {"http": http_vals, "mqtt": mqtt_vals}
    all_values.extend(http_vals + mqtt_vals)
    print(f"  {key}: {len(http_vals):,} HTTP  |  {len(mqtt_vals):,} MQTT")

y_max = float(np.percentile(all_values, 100)) * 1.1 if all_values else 2000
print(f"  Y max (p100 × 1.1): {y_max:.0f} ms")

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
    box_data.append(data[key]["http"] if data[key]["http"] else [0])
    box_data.append(data[key]["mqtt"] if data[key]["mqtt"] else [0])
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
    box_data,
    positions=positions,
    widths=0.62,
    patch_artist=True,
    showfliers=True,
    flierprops=dict(marker="o", markersize=2.5,
                    alpha=FLIER_ALPHA, linestyle="none"),
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

# | ================= Symlog scale ================= |

# linthresh = LINEAR_THRESHOLD: linear between 0 and this value,
# log above it. linscale controls how much vertical space the linear
# region gets relative to each decade of the log region — higher = more.
ax.set_yscale("symlog", linthresh=LINEAR_THRESHOLD, linscale=2.5)
ax.set_ylim(bottom=0, top=y_max)

# Custom y ticks: dense in the linear region, sparse in the log region
linear_ticks = list(range(0, LINEAR_THRESHOLD + 1, 10))
log_ticks    = [t for t in [100, 200, 500, 1000, 2000, 5000, 10000]
                if t <= y_max]
all_ticks    = sorted(set(linear_ticks + log_ticks))
ax.set_yticks(all_ticks)
ax.set_yticklabels([str(t) for t in all_ticks], fontsize=9.5)

# Shade the linear region to make the boundary visible
ax.axhspan(0, LINEAR_THRESHOLD, color="#3b82f6", alpha=0.04, zorder=0)
ax.axhline(LINEAR_THRESHOLD, color="#3b82f6", linewidth=0.8,
           linestyle=":", alpha=0.5, zorder=1)

# Scale labels placed vertically outside the y-axis — one for each region
# These are added after tight_layout via fig.text so they sit in the margin
_scale_labels_pending = True   # drawn after tight_layout call below

# | ================= Styling ================= |

ax.yaxis.grid(True, color=GRID_COLOR, linewidth=0.9, linestyle="-", alpha=0.9)
ax.set_axisbelow(True)
ax.xaxis.grid(False)

for i in range(1, len(SCENARIOS)):
    sep_x = positions[i * 2] - 1
    ax.axvline(sep_x, color=GRID_COLOR, linewidth=1.2, zorder=0)

ax.set_xlim(0.2, max(positions) + 0.8)

ax.set_xticks(scenario_centers)
ax.set_xticklabels(
    [SCENARIOS[k] for k in SCENARIOS],
    fontsize=11.5, color=TEXT_COLOR, fontweight="600",
)
ax.tick_params(axis="x", which="both", bottom=False, colors=TEXT_COLOR)
ax.tick_params(axis="y", colors=TEXT_COLOR)

for spine in ax.spines.values():
    spine.set_edgecolor(SPINE_COLOR); spine.set_linewidth(0.8)

ax.set_ylabel("Latency (ms)", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax.set_xlabel("Scenario", fontsize=13, color=TEXT_COLOR, labelpad=10)
ax.set_title(
    "Context 1 — Latency Distribution per Scenario and Protocol",
    fontsize=15, fontweight="bold", color=TEXT_COLOR, pad=16,
)   

# Legend
http_patch = mpatches.Patch(facecolor=HTTP_FILL, alpha=BOX_ALPHA,
                             edgecolor=HTTP_EDGE, label="HTTP")
mqtt_patch = mpatches.Patch(facecolor=MQTT_FILL, alpha=BOX_ALPHA,
                             edgecolor=MQTT_EDGE, label="MQTT")
legend = ax.legend(
    handles=[http_patch, mqtt_patch],
    loc="lower right", frameon=True, framealpha=0.9,
    facecolor=BG_COLOR, edgecolor=SPINE_COLOR,
    labelcolor=TEXT_COLOR, fontsize=12,
    title="Protocol", title_fontsize=10.5,
)
legend.get_title().set_color(ACCENT_COLOR)

plt.tight_layout(pad=2.0)

# Now that layout is computed, place the linear/log labels just left of the y-axis.
# ax.transData maps data coords → display; fig.transFigure inverts to figure coords.
ax_bbox = ax.get_position()   # axes position in figure fraction

# Y position in figure coords for midpoint of each region
def data_y_to_fig(y_data):
    """Convert a data-space y value to figure-fraction y."""
    y_min, y_max_ = ax.get_ylim()
    # symlog transform: approximate linear fraction for positioning
    import matplotlib.scale as mscale
    trans = ax.transData
    display_y = trans.transform((0, y_data))[1]
    fig_y = fig.transFigure.inverted().transform((0, display_y))[1]
    return fig_y

fig_y_linear = data_y_to_fig(LINEAR_THRESHOLD * 0.38)
fig_y_log    = data_y_to_fig(LINEAR_THRESHOLD * 3.5)
fig_x        = ax_bbox.x0 - 0.042   # just left of the axis

out_path = RESULTS_DIR / "c1_boxplot.png"
plt.savefig(out_path, dpi=180, bbox_inches="tight", facecolor=BG_COLOR)
print(f"\nSaved → {out_path}")
plt.show()