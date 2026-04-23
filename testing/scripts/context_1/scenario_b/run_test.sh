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
TARGET_HOST="${TARGET_HOST:-https://nuc4-pc.local:9443}"
CONTAINERS="${CONTAINERS:-apisix apisix-etcd}"

WARMUP_SECONDS="${WARMUP_SECONDS:-120}"     # 2 minutes
CAPTURE_SECONDS="${CAPTURE_SECONDS:-900}"   # 15 minutes
STATS_INTERVAL="${STATS_INTERVAL:-2}"       # seconds between docker stats polls

# Base number of virtual users. More users = higher achievable throughput.
# constant_throughput splits TARGET_RPS across this many users.
# Needs to be large enough to absorb latency: NUM_USERS >= TARGET_RPS * p99_latency_sec
# 50 users comfortably covers p99 up to 500ms at 100 req/s.
NUM_USERS="${NUM_USERS:-50}"

# Locust file to use — override for scenario A direct testing
LOCUST_FILE="${LOCUST_FILE:-locustfile.py}"

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
echo " Docker stats:   $([ "${SKIP_DOCKER_STATS:-false}" = "true" ] && echo "disabled" || echo "$GATEWAY_SSH_HOST")"
echo " Results dir:    $RESULTS_DIR"
echo " Master CSV:     $MASTER_CSV"
echo "================================================================="

# | ================= Docker stats collector ================= |

STATS_PID=""

if [[ "${SKIP_DOCKER_STATS:-false}" != "true" ]]; then
    echo "timestamp,container,cpu_pct_raw,mem_used,mem_limit,mem_pct_raw,net_in,net_out,block_in,block_out,pids" > "$STATS_CSV"
    echo "[orchestrator] Starting docker stats collection via SSH..."
    # Simpler format — comma-separated directly, no awk parsing needed
    # -n redirects stdin from /dev/null so SSH doesn't consume the script's stdin
    ssh -n -o BatchMode=yes -o ConnectTimeout=5 "$GATEWAY_SSH_HOST" \
        "while true; do \
            docker stats --no-stream --format \
                '{{.Name}},{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.NetIO}},{{.BlockIO}},{{.PIDs}}' \
                $CONTAINERS 2>/dev/null \
            | while IFS= read -r line; do echo \"\$(date +%s),\$line\"; done; \
            sleep $STATS_INTERVAL; \
         done" >> "$STATS_CSV" &
    STATS_PID=$!
else
    echo "[orchestrator] Docker stats collection skipped (SKIP_DOCKER_STATS=true)"
fi

cleanup() {
    echo ""
    if [[ -n "$STATS_PID" ]]; then
        echo "[orchestrator] Stopping stats collection..."
        # Kill the local pipe first
        kill "$STATS_PID" 2>/dev/null || true
        # Kill the remote loop without blocking — fire and forget
        ssh -o BatchMode=yes -o ConnectTimeout=3 "$GATEWAY_SSH_HOST" \
            "pkill -f 'docker stats' 2>/dev/null || true" </dev/null &
        sleep 1
        echo "[orchestrator] Stats collection stopped."
    fi
}
trap cleanup EXIT INT TERM

# | ================= Warmup ================= |

echo ""
echo "[orchestrator] === WARMUP (${WARMUP_SECONDS}s at $TARGET_RPS req/s) ==="
TARGET_RPS="$TARGET_RPS" NUM_USERS="$NUM_USERS" \
    locust -f "$LOCUST_FILE" \
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
# Allow non-zero exit from Locust (it returns 1 when any requests fail).
# We still want to aggregate and record results in that case.
TARGET_RPS="$TARGET_RPS" NUM_USERS="$NUM_USERS" \
    locust -f "$LOCUST_FILE" \
        --host "$TARGET_HOST" \
        -u "$NUM_USERS" -r "$NUM_USERS" \
        -t "${CAPTURE_SECONDS}s" \
        --headless \
        --csv "$LOCUST_CSV_PREFIX" \
        --csv-full-history \
        2>&1 | tee "$CAPTURE_LOG" || true

# | ================= Aggregate docker stats ================= |

# Compute mean and max CPU% and actual RAM (MiB) per container.
# CSV format from docker stats: timestamp,name,cpu%,mem_used/mem_limit,mem%,net,block,pids
# $3 = cpu% (e.g. "0.23%"), $4 = mem field (e.g. "128.3MiB / 14.89GiB")
# We store CPU as percentage and RAM as MiB (actual usage, not percentage).
DOCKER_SUMMARY=""
if [[ "${SKIP_DOCKER_STATS:-false}" != "true" && -f "$STATS_CSV" ]]; then
    for container in $CONTAINERS; do
        line=$(awk -F',' -v c="$container" '
            $2 == c && $3 != "" {
                # CPU — strip % sign
                cpu = $3; gsub(/%/, "", cpu); cpu += 0

                # RAM — parse the used portion of "128.3MiB / 14.89GiB"
                # Take everything before " / ", strip the unit, convert to MiB
                mem_str = $4; sub(/ \/ .*/, "", mem_str)
                mem_val = mem_str; gsub(/[A-Za-z]/, "", mem_val); mem_val += 0
                if      (mem_str ~ /GiB/) mem_mib = mem_val * 1024
                else if (mem_str ~ /MiB/) mem_mib = mem_val
                else if (mem_str ~ /kB/)  mem_mib = mem_val / 1024
                else if (mem_str ~ /MB/)  mem_mib = mem_val * 0.9537  # 1 MB = 0.9537 MiB
                else                      mem_mib = mem_val

                cpu_sum += cpu; cpu_n++
                mem_sum += mem_mib; mem_n++
                if (cpu    > cpu_max) cpu_max = cpu
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
    # No docker stats — emit empty columns so the CSV row stays consistent
    for container in $CONTAINERS; do
        DOCKER_SUMMARY="${DOCKER_SUMMARY},,,,"
    done
fi

# | ================= Aggregate into master CSV ================= |

# Pull per-protocol rows and the overall Aggregated row from Locust stats.
# Locust 2.x columns:
#   Type,Name,Request Count,Failure Count,Median,Avg,Min,Max,
#   Avg Content Size,Req/s,Fail/s,50%,66%,75%,80%,90%,95%,98%,99%,99.9%,99.99%,100%
EMPTY_METRICS=",,,,,,,,,,,,,,,,,,,,,"

extract_row() {
    local name="$1"
    local row
    row=$(awk -F',' -v n="$name" '$2 == n { print; exit }' "${LOCUST_CSV_PREFIX}_stats.csv")
    if [[ -z "$row" ]]; then
        echo "$EMPTY_METRICS"
    else
        # Strip Type and Name columns, then strip any 
 or 
 that crept in
        echo "$row" | cut -d',' -f3- | tr -d $'\r\n'
    fi
}

# Support both scenario A names (direct/*) and B/C/D names (gateway/*)
HTTP_ROW=$(grep -m1 'http' "${LOCUST_CSV_PREFIX}_stats.csv" | awk -F',' '$2 ~ /http/ && $2 != "Name"' || true)
MQTT_ROW=$(grep -m1 'mqtt' "${LOCUST_CSV_PREFIX}_stats.csv" | awk -F',' '$2 ~ /mqtt/ && $2 != "Name"' || true)

# Strip carriage returns from name fields (Locust CSV may have Windows line endings)
HTTP_NAME=$(echo "$HTTP_ROW" | cut -d',' -f2 | tr -d $'\r\n')
MQTT_NAME=$(echo "$MQTT_ROW" | cut -d',' -f2 | tr -d $'\r\n')

HTTP_METRICS=$(extract_row "$HTTP_NAME")
MQTT_METRICS=$(extract_row "$MQTT_NAME")
AGG_METRICS=$(extract_row "Aggregated")

# Strip any trailing newlines from docker summary
DOCKER_SUMMARY=$(echo "$DOCKER_SUMMARY" | tr -d $'\r\n')

# Build the docker stats header (4 cols per container)
DOCKER_HEADER=""
for container in $CONTAINERS; do
    # cpu = percentage (%), ram = actual usage in MiB
    DOCKER_HEADER="${DOCKER_HEADER},${container}_cpu_avg_pct,${container}_cpu_max_pct,${container}_ram_avg_mib,${container}_ram_max_mib"
done

# Metric column suffix (same set repeated for http, mqtt, aggregated)
METRIC_COLS="request_count,failure_count,median_ms,avg_ms,min_ms,max_ms,avg_content_size,achieved_rps,failures_per_sec,p50,p66,p75,p80,p90,p95,p98,p99,p99_9,p99_99,p100"

if [[ ! -f "$MASTER_CSV" ]]; then
    # Prefix each metric block with its protocol so columns are unambiguous
    HTTP_HEADER=$(echo "$METRIC_COLS" | sed 's/[^,]*/http_&/g')
    MQTT_HEADER=$(echo "$METRIC_COLS" | sed 's/[^,]*/mqtt_&/g')
    AGG_HEADER=$(echo "$METRIC_COLS"  | sed 's/[^,]*/agg_&/g')
    echo "scenario,target_rps,payload_size,timestamp,num_users,capture_seconds,${HTTP_HEADER},${MQTT_HEADER},${AGG_HEADER}${DOCKER_HEADER}" > "$MASTER_CSV"
fi

echo "${SCENARIO},${TARGET_RPS},${PAYLOAD_SIZE},${TIMESTAMP},${NUM_USERS},${CAPTURE_SECONDS},${HTTP_METRICS},${MQTT_METRICS},${AGG_METRICS}${DOCKER_SUMMARY}" >> "$MASTER_CSV"

# | ================= Summary ================= |

echo ""
echo "[orchestrator] === DONE ==="
echo " Results dir:    $RESULTS_DIR"
echo " Row appended:   $MASTER_CSV"
echo ""
echo "=== HTTP metrics ==="
echo "  $HTTP_METRICS"
echo "=== MQTT metrics ==="
echo "  $MQTT_METRICS"
echo "=== Aggregated ==="
echo "  $AGG_METRICS"