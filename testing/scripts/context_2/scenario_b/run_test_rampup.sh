#!/usr/bin/env bash
# ============================================================================
# Runs a ramp-up test for context 2, with optional distributed Locust workers.
#
# Usage:
#   ./run_test_rampup.sh <scenario_name> <payload_size>
#
# Example (single process):
#   START_RPS=10 END_RPS=2000 STEP_RPS=50 STEP_DURATION_S=20 \
#       ./run_test_rampup.sh context2_full medium
#
# Example (distributed, 3 workers — one per core):
#   NUM_WORKERS=3 START_RPS=10 END_RPS=4000 STEP_RPS=50 STEP_DURATION_S=20 \
#       ./run_test_rampup.sh context2_full medium
#
# Env vars:
#   NUM_WORKERS       Number of Locust worker processes (default: 1 = no master/worker split)
#   START_RPS         First step RPS (default: 10)
#   END_RPS           Last step RPS (default: 300)
#   STEP_RPS          RPS increment per step (default: 10)
#   STEP_DURATION_S   Seconds per step (default: 30)
#   PER_USER_RPS      Req/s per Locust user (default: 1.0)
# ============================================================================

set -euo pipefail

# | ================= Configuration ================= |

GATEWAY_SSH_HOST="${GATEWAY_SSH_HOST:-nuc4@nuc4-pc.local}"
TARGET_HOST="${TARGET_HOST:-http://192.168.50.4:9080}"
CONTAINERS="${CONTAINERS:-apisix apisix-etcd}"
STATS_INTERVAL="${STATS_INTERVAL:-2}"
LOCUST_FILE="${LOCUST_FILE:-locustfile_rampup.py}"
MASTER_CSV="${MASTER_CSV:-./results/rampup_runs.csv}"
SKIP_DOCKER_STATS="${SKIP_DOCKER_STATS:-false}"

START_RPS="${START_RPS:-10}"
END_RPS="${END_RPS:-300}"
STEP_RPS="${STEP_RPS:-10}"
STEP_DURATION_S="${STEP_DURATION_S:-30}"
PER_USER_RPS="${PER_USER_RPS:-1.0}"
NUM_WORKERS="${NUM_WORKERS:-1}"   # > 1 enables distributed mode

# | ================= Args ================= |

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <scenario_name> <payload_size>"
    exit 1
fi

SCENARIO="$1"
PAYLOAD_SIZE="$2"

STEPS=$(python3 -c "print(int(($END_RPS - $START_RPS) / $STEP_RPS) + 1)")
TOTAL_S=$(python3 -c "print(int($STEPS * $STEP_DURATION_S))")
MAX_USERS=$(python3 -c "import math; print(max(1, math.ceil($END_RPS / $PER_USER_RPS)))")

# | ================= Output layout ================= |

TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
RESULTS_DIR="./results/${SCENARIO}_${PAYLOAD_SIZE}_ramp${START_RPS}-${END_RPS}_${TIMESTAMP}"
mkdir -p "$RESULTS_DIR"
mkdir -p "$(dirname "$MASTER_CSV")"

STATS_CSV="${RESULTS_DIR}/docker_stats.csv"
LOCUST_CSV_PREFIX="${RESULTS_DIR}/locust"
RUN_LOG="${RESULTS_DIR}/run.log"

echo "================================================================="
echo " Scenario:        $SCENARIO"
echo " Payload size:    $PAYLOAD_SIZE"
echo " Ramp:            $START_RPS → $END_RPS req/s (step: $STEP_RPS)"
echo " Step duration:   ${STEP_DURATION_S}s × $STEPS steps = ${TOTAL_S}s total"
echo " Max users:       $MAX_USERS"
echo " Per-user RPS:    $PER_USER_RPS"
echo " Workers:         $NUM_WORKERS $([ "$NUM_WORKERS" -gt 1 ] && echo "(distributed)" || echo "(single process)")"
echo " Target host:     $TARGET_HOST"
echo " Docker stats:    $([ "$SKIP_DOCKER_STATS" = "true" ] && echo "disabled" || echo "$GATEWAY_SSH_HOST")"
echo " Results dir:     $RESULTS_DIR"
echo "================================================================="

# | ================= Docker stats collector ================= |

STATS_PID=""

if [[ "$SKIP_DOCKER_STATS" != "true" ]]; then
    echo "timestamp,container,cpu_pct,mem_used,mem_limit,mem_pct,net_io,block_io,pids" > "$STATS_CSV"
    echo "[rampup] Starting docker stats collection..."
    ssh -n -o BatchMode=yes -o ConnectTimeout=5 "$GATEWAY_SSH_HOST" \
        "while true; do \
            docker stats --no-stream --format \
                '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}' \
                $CONTAINERS 2>/dev/null \
            | while IFS= read -r line; do echo \"\$(date +%s),\$line\"; done; \
            sleep $STATS_INTERVAL; \
         done" >> "$STATS_CSV" &
    STATS_PID=$!
fi

WORKER_PIDS=()

cleanup() {
    echo ""
    # Kill workers
    for pid in "${WORKER_PIDS[@]:-}"; do
        kill "$pid" 2>/dev/null || true
    done
    if [[ -n "$STATS_PID" ]]; then
        echo "[rampup] Stopping stats collection..."
        kill "$STATS_PID" 2>/dev/null || true
        ssh -o BatchMode=yes -o ConnectTimeout=3 "$GATEWAY_SSH_HOST" \
            "pkill -f 'docker stats' 2>/dev/null || true" </dev/null &
        sleep 1
    fi
}
trap cleanup EXIT INT TERM

# | ================= Common env for all Locust processes ================= |

LOCUST_ENV=(
    START_RPS="$START_RPS"
    END_RPS="$END_RPS"
    STEP_RPS="$STEP_RPS"
    STEP_DURATION_S="$STEP_DURATION_S"
    PER_USER_RPS="$PER_USER_RPS"
    RESULTS_DIR="$RESULTS_DIR"
)

# | ================= Run ================= |

echo ""

if [[ "$NUM_WORKERS" -le 1 ]]; then
    # ---- Single process mode (original behaviour) ----
    echo "[rampup] Starting single-process ramp-up test..."

    env "${LOCUST_ENV[@]}" \
        locust -f "$LOCUST_FILE" \
            --host "$TARGET_HOST" \
            -u "$MAX_USERS" -r "$MAX_USERS" \
            --headless \
            --csv "$LOCUST_CSV_PREFIX" \
            --csv-full-history \
            2>&1 | tee "$RUN_LOG" || true

else
    # ---- Distributed mode ----
    # Master: holds the LoadTestShape, collects stats, writes CSV.
    # Workers: each runs its own gevent loop on a separate core.
    echo "[rampup] Starting distributed ramp-up test ($NUM_WORKERS workers)..."

    # Start workers in background first (master waits for them)
    for i in $(seq 1 "$NUM_WORKERS"); do
        env "${LOCUST_ENV[@]}" \
            locust -f "$LOCUST_FILE" \
                --host "$TARGET_HOST" \
                --worker \
                --master-host 127.0.0.1 \
                >> "${RESULTS_DIR}/worker_${i}.log" 2>&1 &
        WORKER_PIDS+=($!)
        echo "[rampup] Worker $i started (PID ${WORKER_PIDS[-1]})"
    done

    # Give workers a moment to start up before master connects
    sleep 2

    # Master: waits for all workers, then runs the shape
    env "${LOCUST_ENV[@]}" \
        locust -f "$LOCUST_FILE" \
            --host "$TARGET_HOST" \
            --master \
            --expect-workers "$NUM_WORKERS" \
            --headless \
            --csv "$LOCUST_CSV_PREFIX" \
            --csv-full-history \
            2>&1 | tee "$RUN_LOG" || true

    # Workers exit automatically when master stops
    for pid in "${WORKER_PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done
fi

echo ""
echo "[rampup] Run complete."

# | ================= Aggregate ================= |

DOCKER_SUMMARY=""
if [[ "$SKIP_DOCKER_STATS" != "true" && -f "$STATS_CSV" ]]; then
    for container in $CONTAINERS; do
        line=$(awk -F',' -v c="$container" '
            $2 == c && $3 != "" {
                cpu = $3; gsub(/%/, "", cpu)
                mem_str = $4; sub(/ \/ .*/, "", mem_str)
                mem_val = mem_str; gsub(/[A-Za-z]/, "", mem_val); mem_val += 0
                if      (mem_str ~ /GiB/) mem_mib = mem_val * 1024
                else if (mem_str ~ /MiB/) mem_mib = mem_val
                else if (mem_str ~ /kB/)  mem_mib = mem_val / 1024
                else if (mem_str ~ /MB/)  mem_mib = mem_val * 0.9537
                else                      mem_mib = mem_val
                cpu_sum += cpu+0; cpu_n++
                mem_sum += mem_mib; mem_n++
                if (cpu+0   > cpu_max) cpu_max = cpu+0
                if (mem_mib > mem_max) mem_max = mem_mib
            }
            END {
                if (cpu_n > 0)
                    printf "%.2f,%.2f,%.1f,%.1f", cpu_sum/cpu_n, cpu_max, mem_sum/mem_n, mem_max
                else
                    printf ",,,"
            }' "$STATS_CSV")
        DOCKER_SUMMARY="${DOCKER_SUMMARY},${line}"
    done
else
    for container in $CONTAINERS; do
        DOCKER_SUMMARY="${DOCKER_SUMMARY},,,,"
    done
fi
DOCKER_SUMMARY=$(echo "$DOCKER_SUMMARY" | tr -d '\r\n')

EMPTY_METRICS=",,,,,,,,,,,,,,,,,,,,,"

extract_row() {
    local name="$1"
    local row
    row=$(awk -F',' -v n="$name" '$2 == n { print; exit }' \
        "${LOCUST_CSV_PREFIX}_stats.csv" 2>/dev/null || true)
    if [[ -z "$row" ]]; then
        echo "$EMPTY_METRICS"
    else
        echo "$row" | cut -d',' -f3- | tr -d '\r\n'
    fi
}

HTTP_METRICS=$(extract_row "ramp/http")
MQTT_METRICS=$(extract_row "ramp/mqtt")
AGG_METRICS=$(extract_row  "Aggregated")

METRIC_COLS="request_count,failure_count,median_ms,avg_ms,min_ms,max_ms,avg_content_size,achieved_rps,failures_per_sec,p50,p66,p75,p80,p90,p95,p98,p99,p99_9,p99_99,p100"

DOCKER_HEADER=""
for container in $CONTAINERS; do
    DOCKER_HEADER="${DOCKER_HEADER},${container}_cpu_avg_pct,${container}_cpu_max_pct,${container}_ram_avg_mib,${container}_ram_max_mib"
done

HTTP_HEADER=$(echo "$METRIC_COLS" | sed 's/[^,]*/http_&/g')
MQTT_HEADER=$(echo "$METRIC_COLS" | sed 's/[^,]*/mqtt_&/g')
AGG_HEADER=$(echo  "$METRIC_COLS" | sed 's/[^,]*/agg_&/g')

if [[ ! -f "$MASTER_CSV" ]]; then
    echo "scenario,payload_size,timestamp,start_rps,end_rps,step_rps,step_duration_s,per_user_rps,${HTTP_HEADER},${MQTT_HEADER},${AGG_HEADER}${DOCKER_HEADER}" \
        > "$MASTER_CSV"
fi

echo "${SCENARIO},${PAYLOAD_SIZE},${TIMESTAMP},${START_RPS},${END_RPS},${STEP_RPS},${STEP_DURATION_S},${PER_USER_RPS},${HTTP_METRICS},${MQTT_METRICS},${AGG_METRICS}${DOCKER_SUMMARY}" \
    >> "$MASTER_CSV"

echo ""
echo "[rampup] === DONE ==="
echo " Results dir : $RESULTS_DIR"
echo " Row appended: $MASTER_CSV"
echo " History CSV : ${LOCUST_CSV_PREFIX}_stats_history.csv"
echo ""
echo "=== Aggregated Locust row ==="
echo "$AGG_METRICS"