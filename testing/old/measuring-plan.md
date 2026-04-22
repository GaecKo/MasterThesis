# Performance Testing: Metrics and Representation

This section defines what to measure, where to measure, and how to represent the results for all performance scenarios (A → C5). All scenarios will be tested under **single-threaded** and **multi-threaded** configurations.

---

## 1 Metrics to Measure

| Metric | Description | Notes |
|--------|-------------|-------|
| **End-to-End RTT** | Device <-> Gateway <-> Backend | Captures user-perceived latency |
| **Gateway Processing Time** | Time spent inside APISIX | Isolates gateway overhead (security, routing, filtering) |
| **Throughput** | Requests per second | Measured at gateway entry point or device |
| **Resource Utilization** | CPU%, Memory%, Network I/O | Especially on NUC 4 (API Gateway) |
| **Error Rate / Request Failures** | Requests dropped or timed out | Important under overload conditions |


---

## 2 Measurement Locations

| Metric | Notes |
|--------|-------|
| RTT / End-to-End latency | request sent / response received |
| Gateway Processing Time | Measures internal pipeline time |
| Throughput | Requests successfully processed per second |
| CPU / Memory | For multithreading and load evaluation |
| Error Rate | Failed or rejected requests |

---

## 3 Data Representation

### A. Tables

Summarize numeric results for each scenario and execution mode:

| Scenario | Avg RTT (ms) | p95 (ms) | p99 (ms) | Max Throughput (req/s) | CPU % |
|----------|-------------|----------|----------|-----------------------|-------|

> Include separate columns or tables for single-threaded vs multi-threaded runs.

### B. Latency vs Throughput Graphs

- **X-axis:** Throughput (requests/sec)  
- **Y-axis:** Latency (ms)  
- **Curves:** Different scenarios (A, B, C1, C4, C5)  
- **Optional:** Dashed vs solid lines for single-threaded vs multi-threaded execution  

---

## 4 Measurement Procedure

1. **Baseline (Scenario A)**
   - Low load → 10 req/s
   - Medium load → 100 req/s
   - High load → until backend saturation

2. **Add Gateway (Scenario B)**
   - Repeat baseline load levels
   - Capture gateway overhead

3. **Incremental Security (C1 → C5)**
   - Repeat same load levels
   - Measure additional latency and throughput cost

4. **Multi-threaded Execution**
   - Repeat steps 2 & 3 under multi-threaded mode
   - Compare scalability and performance gains

5. **Stress / Overload Testing**
   - Flood traffic for C4 vs C5
   - Measure latency, error rate, and throughput saturation

---

## Summary

- Measure RTT, gateway processing, throughput, and resource utilization.  
- Use **tables** for numeric summaries.  
- Use **latency vs throughput graphs** to show trends and scalability.  
- Include **single-threaded vs multi-threaded lines**.  
- Include **stress tests** to evaluate rate limiting and overload protection.