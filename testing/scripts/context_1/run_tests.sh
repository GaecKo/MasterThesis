#!/usr/bin/env bash
# ============================================================================
# Orchestrates a single performance test run.
#
# Flow:
#   1. Starts SSH-based docker stats collection on the gateway NUC (background)
#   2. Runs Locust for WARMUP_SECONDS with TARGET_RPS to warm caches
#   3. Runs Locust for CAPTURE_SECONDS at TARGET_RPS with CSV output
#   4. Stops metrics collection
#   5. Aggregates everything into a single flat CSV row
#
# Usage:
#   ./run_test.sh <scenario_name> <nb_req_per_sec> <payload_size>
#
# Example:
#   ./run_test.sh scenarioD 100 medium
# ============================================================================

set -euo pipefail

# | ================= Placeholders ================= |

GATEWAY_SSH_HOST="${GATEWAY_SSH_HOST:-nuc4@nuc4-pc.local}"
TARGET_HOST="${TARGET_HOST:-http://nuc4-pc.local:9080}"
CONTAINERS="${CONTAINERS:-apisix mongodb mosquitto etcd}"

WARMUP_SECONDS="${WARMUP_SECONDS:-120}"     # 2 minutes
CAPTURE_SECONDS="${CAPTURE_SECONDS:-900}"   # 15 minutes
STATS_INTERVAL="${STATS_INTERVAL:-2}"       # seconds between docker stats polls

# Base number of virtual users. More users = higher achievable throughput.
# constant_throughput splits TARGET_RPS across this many users.
# Needs to be large enough to absorb latency: NUM_USERS >= TARGET_RPS * p99_latency_sec
# 50 users comfortably covers p99 up to 500ms at 100 req/s.
NUM_USERS="${NUM_USERS:-50}"

# Master CSV where every run appends one row
MASTER_CSV="${MASTER_CSV:-./results/all_runs.csv}"

# | ================= Args ================= |

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <scenario_name> <nb_req_per_sec> <payload_size>"
    echo "  scenario_name    e.g. 'scenarioA', 'scenarioD'"
    echo "  nb_req_per_sec   integer target throughput"
    echo "  payload_size     'medium' or 'large'"
    exit 1
fi

SCENARIO="$1"
TARGET_RPS="$2"
PAYLOAD_SIZE="$3"

if [[ "$PAYLOAD_SIZE" != "medium" && "$PAYLOAD_SIZE" != "large" ]]; then
    echo "[ERROR] payload_size must be 'medium' or 'large'"
    exit 1
fi

# | ================= Output layout ================= |

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
RESULTS_DIR="./results/${SCENARIO}_${PAYLOAD_SIZE}_${TARGET_RPS}rps_${TIMESTAMP}"
mkdir -p "$RESULTS_DIR"
mkdir -p "$(dirname "$MASTER_CSV")"

STATS_CSV="${RESULTS_DIR}/docker_stats.csv"
LOCUST_CSV_PREFIX="${RESULTS_DIR}/locust"
WARMUP_LOG="${RESULTS_DIR}/warmup.log"
CAPTURE_LOG="${RESULTS_DIR}/capture.log"

echo "================================================================="
echo " Scenario:       $SCENARIO"
echo " Target RPS:     $TARGET_RPS"
echo " Payload size:   $PAYLOAD_SIZE"
echo " Virtual users:  $NUM_USERS"
echo " Warmup:         ${WARMUP_SECONDS}s"
echo " Capture:        ${CAPTURE_SECONDS}s"
echo " Target host:    $TARGET_HOST"
echo " Gateway SSH:    $GATEWAY_SSH_HOST"
echo " Results dir:    $RESULTS_DIR"
echo " Master CSV:     $MASTER_CSV"
echo "================================================================="

# | ================= Docker stats collector ================= |

echo "timestamp,container,cpu_pct_raw,mem_used,mem_limit,mem_pct_raw,net_in,net_out,block_in,block_out,pids" > "$STATS_CSV"

echo "[orchestrator] Starting docker stats collection via SSH..."
ssh -o BatchMode=yes -o ConnectTimeout=5 "$GATEWAY_SSH_HOST" \
    "while true; do \
        docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}|{{.NetIO}}|{{.BlockIO}}|{{.PIDs}}' $CONTAINERS 2>/dev/null \
        | while IFS= read -r line; do echo \"\$(date +%s)|\$line\"; done; \
        sleep $STATS_INTERVAL; \
     done" \
     | awk -F'|' 'BEGIN{OFS=","} {
           split($4, mem, " / ");  gsub(/ /, "", mem[1]); gsub(/ /, "", mem[2]);
           split($6, net, " / ");  gsub(/ /, "", net[1]); gsub(/ /, "", net[2]);
           split($7, blk, " / ");  gsub(/ /, "", blk[1]); gsub(/ /, "", blk[2]);
           gsub(/%/, "", $3); gsub(/%/, "", $5);
           print $1,$2,$3,mem[1],mem[2],$5,net[1],net[2],blk[1],blk[2],$8
         }' >> "$STATS_CSV" &
STATS_PID=$!

cleanup() {
    echo ""
    echo "[orchestrator] Stopping stats collection..."
    if kill -0 "$STATS_PID" 2>/dev/null; then
        kill "$STATS_PID" 2>/dev/null || true
        wait "$STATS_PID" 2>/dev/null || true
    fi
    ssh -o BatchMode=yes -o ConnectTimeout=5 "$GATEWAY_SSH_HOST" \
        "pkill -f 'docker stats --no-stream' || true" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# | ================= Warmup ================= |

echo ""
echo "[orchestrator] === WARMUP (${WARMUP_SECONDS}s at $TARGET_RPS req/s) ==="
TARGET_RPS="$TARGET_RPS" NUM_USERS="$NUM_USERS" \
    locust -f locustfile.py \
        --host "$TARGET_HOST" \
        -u "$NUM_USERS" -r "$NUM_USERS" \
        -t "${WARMUP_SECONDS}s" \
        --headless --only-summary \
        > "$WARMUP_LOG" 2>&1 || {
            echo "[orchestrator] Warmup failed — see $WARMUP_LOG"
            exit 1
        }
echo "[orchestrator] Warmup complete."

# | ================= Capture ================= |

echo ""
echo "[orchestrator] === CAPTURE (${CAPTURE_SECONDS}s at $TARGET_RPS req/s) ==="
TARGET_RPS="$TARGET_RPS" NUM_USERS="$NUM_USERS" \
    locust -f locustfile.py \
        --host "$TARGET_HOST" \
        -u "$NUM_USERS" -r "$NUM_USERS" \
        -t "${CAPTURE_SECONDS}s" \
        --headless \
        --csv "$LOCUST_CSV_PREFIX" \
        --csv-full-history \
        2>&1 | tee "$CAPTURE_LOG"

# | ================= Aggregate docker stats ================= |

# Compute mean and max CPU% and mem% across the capture window, per container.
# Keep it simple with awk — no Python deps needed on the runner host.
DOCKER_SUMMARY=""
for container in $CONTAINERS; do
    line=$(awk -F',' -v c="$container" '
        $2 == c && $3 != "" {
            cpu_sum += $3; cpu_n++
            if ($3+0 > cpu_max) cpu_max = $3+0
            if ($6+0 > mem_max) mem_max = $6+0
            mem_sum += $6; mem_n++
        }
        END {
            if (cpu_n > 0)
                printf "%.2f,%.2f,%.2f,%.2f", cpu_sum/cpu_n, cpu_max, mem_sum/mem_n, mem_max
            else
                printf ",,,"
        }' "$STATS_CSV")
    DOCKER_SUMMARY="${DOCKER_SUMMARY},${line}"
done

# | ================= Aggregate into master CSV ================= |

# Pull the Aggregated row from Locust stats. Columns (Locust 2.x):
#   Type,Name,Request Count,Failure Count,Median Response Time,Average Response Time,
#   Min Response Time,Max Response Time,Average Content Size,Requests/s,Failures/s,
#   50%,66%,75%,80%,90%,95%,98%,99%,99.9%,99.99%,100%
LOCUST_AGG=$(awk -F',' '$2 == "Aggregated" { print; exit }' "${LOCUST_CSV_PREFIX}_stats.csv")

if [[ -z "$LOCUST_AGG" ]]; then
    echo "[orchestrator] WARNING: no Aggregated row found in ${LOCUST_CSV_PREFIX}_stats.csv"
    LOCUST_AGG=",,,,,,,,,,,,,,,,,,,,,"
fi

# Strip the first two columns (Type, Name) — we only want the numbers
LOCUST_METRICS=$(echo "$LOCUST_AGG" | cut -d',' -f3-)

# Build the header once. The container metrics expand into 4 cols per container
# (cpu_avg, cpu_max, mem_avg, mem_max).
DOCKER_HEADER=""
for container in $CONTAINERS; do
    DOCKER_HEADER="${DOCKER_HEADER},${container}_cpu_avg,${container}_cpu_max,${container}_mem_avg,${container}_mem_max"
done

if [[ ! -f "$MASTER_CSV" ]]; then
    echo "scenario,target_rps,payload_size,timestamp,num_users,capture_seconds,request_count,failure_count,median_ms,avg_ms,min_ms,max_ms,avg_content_size,achieved_rps,failures_per_sec,p50,p66,p75,p80,p90,p95,p98,p99,p99_9,p99_99,p100${DOCKER_HEADER}" > "$MASTER_CSV"
fi

# Append the run row
echo "${SCENARIO},${TARGET_RPS},${PAYLOAD_SIZE},${TIMESTAMP},${NUM_USERS},${CAPTURE_SECONDS},${LOCUST_METRICS}${DOCKER_SUMMARY}" >> "$MASTER_CSV"

# | ================= Summary ================= |

echo ""
echo "[orchestrator] === DONE ==="
echo " Results dir:    $RESULTS_DIR"
echo " Row appended:   $MASTER_CSV"
echo ""
echo "=== Aggregated Locust row ==="
echo "$LOCUST_AGG"