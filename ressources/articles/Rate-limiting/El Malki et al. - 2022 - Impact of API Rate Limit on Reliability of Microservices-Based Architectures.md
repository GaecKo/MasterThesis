# Summary of “Impact of API Rate Limit on Reliability of Microservices-Based Architectures”
**Authors:** Amine El Malki, Uwe Zdun (University of Vienna), Cesare Pautasso (University of Lugano)  
**Published:** Research Group Software Architecture, University of Vienna

---

## 🧩 Overview
This paper investigates **how API rate limiting impacts the reliability of microservices-based architectures** from the perspective of API clients. It introduces an **analytical model** that predicts the success and failure rates of backend services under varying rate limit and workload conditions. The model is validated empirically on a **private cloud** and **Google Cloud Platform (GCP)**.

---

## 🎯 Research Objectives

- **RQ1:** How can we accurately predict API Rate Limit impacts on reliability properties of microservice-based systems from the API client’s perspective?  
- **RQ2:** What are the effects of API Rate Limit on those reliability properties?

---

## ⚙️ Background: Rate Limiting and API Gateways

- **Rate limiting** is a key **API gateway pattern** used to:
  - Prevent service overload or DoS attacks.
  - Control client behavior and enforce usage/billing policies.
  - Improve reliability and availability.
- Implemented via API gateways such as **Kong**.
- Common **rate-limiting algorithms**:  
  - **Token Bucket**
  - **Leaky Bucket**
  - **Fixed Window**
  - **Sliding Window**
- Two main types:
  - **Backend rate limiting:** constrained by backend physical capacity (TPS).
  - **Application rate limiting:** restricts requests per user/time window — the focus of this paper.

---

## 🧮 Analytical Model Summary

- Reliability is modeled as a **Bernoulli process**, where each time interval represents a Bernoulli trial.
- Client-side parameters:
  - `SUi`: share of users by usage level  
  - `RPMi`: requests per minute per user group  
  - `LOADi`, `SLEEPi`: active vs. idle times  
- Server-side parameters:
  - `TRT_success`, `TRT_failure`, `TRT_ratelimit`
- Derived metrics:
  - **SR:** Success rate per minute  
  - **NRL_FR:** Non-rate-limiting failure rate  
  - **RL_FR:** Rate-limiting failure rate

📘 **Key Insight:**  
Rate limiting can reduce overall failures by filtering excessive requests, but overly strict limits degrade success rates. The model helps **fine-tune rate limits adaptively** based on workload.

---

## 🧪 Experimental Setup

### Infrastructure
- **Private Cloud:**
  - 4 Ubuntu VMs with Minikube, Kubernetes, and **Istio service mesh**.
  - **Kong API Gateway** used for ingress and rate limiting.
  - Monitoring via **Prometheus** and **Grafana**.
- **Google Cloud Platform:**
  - Google Kubernetes Engine (GKE) cluster with Istio and Kong.

### Workload
- Simulated **e-commerce**-style workloads (login, product, basket, payment).
- 10 workload configurations (C1–C10) × 5 rate limits (5, 25, 75, 150, ∞ req/min).
- Each experiment repeated 50 times; total runtime > 2000 hours.

---

## 📊 Key Results

| Platform | Success Rate (SR%) | Non-Rate-Limiting Failures (NRL_FR%) | Rate-Limiting Failures (RL_FR%) | Max Error |
|-----------|--------------------|--------------------------------------|----------------------------------|------------|
| Private Cloud | 17.7% | 2% | 16.9% | < 18% |
| GCP | 16.7% | 3.7% | 16.2% | < 17% |

- **Prediction Error (MAPE)**: ≤ 17–18%, well below the 30% acceptable threshold for cloud systems.
- **Finding:** Reliability (success rate) increases with moderate rate limiting but decreases when limits are too restrictive.
- **Optimal range:** Around **75–150 requests/min** for most workloads.
- **Disabling rate limiting** may be best for low-load scenarios.

---

## ⚠️ Threats to Validity
- Limited user and service diversity; homogeneous RESTful services.
- Focused on **API-level reliability**, not backend specifics.
- Limited to e-commerce-like workloads — results may differ for other domains.

---

## ✅ Conclusions

1. Developed and empirically validated a **predictive model** for rate-limiting impact on API reliability.  
2. Demonstrated that **Rate Limiting improves reliability** up to a threshold, after which it can degrade performance.  
3. Provided a foundation for **adaptive rate-limit tuning** in cloud-native and microservice-based environments.  
4. Suggested future work on integrating more diverse workloads, configurations, and resource management aspects.

---

## 🧠 Relevance to API Gateway Research
- Directly ties **rate limiting**, a core **API Gateway pattern**, to **system reliability**.
- Empirical evidence shows **Kong API Gateway** and **Istio** configurations influence reliability outcomes.
- The proposed model can guide **architectural decision-making** on API Gateway configuration during design.
- Supports **data-driven rate limit tuning** to balance reliability, availability, and user experience.

---

## 🔗 References of Note
- [23] C. Richardson, “Pattern: API Gateway / Backends for Frontends,” microservices.io (2020).  
- [5] O. Zimmermann et al., “Microservice API Patterns: Rate Limit,” 2020.  
- [6] Google Cloud, “Techniques for Enforcing Rate Limits,” 2020.  
- [31] K. Chen, “Kong Ingress Controller and Service Mesh: Setting up Ingress to Istio on Kubernetes,” 2020.

---

