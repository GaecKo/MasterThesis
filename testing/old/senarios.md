# Performance Testing Scenarios

> **Execution Mode:**  
All scenarios are executed under two configurations:
- **Single-threaded execution**
- **Multi-threaded execution**

---

## A — No Gateway
**Architecture:** Direct communication (Device → Backend)  
**Execution Modes:** Single-threaded / Multi-threaded  

---

## B — Gateway Without Security
**Architecture:** API Gateway enabled  
**Execution Modes:** Single-threaded / Multi-threaded  

---

## C1 — TLS + Authentication / Authorization
**Architecture:** Gateway + TLS + Authentication / Authorization  
**Execution Modes:** Single-threaded / Multi-threaded  

---

## C4 — TLS + Authentication / Authorization + Traffic Overload Protection + Rate Limiting (Full security)
**Architecture:** Gateway + TLS + Authentication + Traffic Overload Protection + Rate Limiting  
**Execution Modes:** Single-threaded / Multi-threaded  

---
