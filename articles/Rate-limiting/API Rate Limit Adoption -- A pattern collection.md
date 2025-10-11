# Summary of *API Rate Limit Adoption – A Pattern Collection*

**Reference**: Souhaila Serbout, Amine El Malki, Cesare Pautasso, Uwe Zdun. *API Rate Limit Adoption – A Pattern Collection.* EuroPLoP 2023. https://doi.org/10.1145/3628034.3628039

---

## 1. Purpose
The paper explores **patterns for adopting API Rate Limiting**, from documentation to implementation. It provides guidance for developers on selecting the most suitable rate limit **method, scope, and granularity**, aiming to enhance **scalability, security, reliability, and service availability** of APIs.

---

## 2. The API Rate Limit Pattern
- **Goal**: Prevent abusive/excessive usage by API clients to protect resources and ensure fairness.
- **Forces**: Trade-offs between performance, reliability, security, economic aspects, and client experience.
- **Solution**: Limit requests per time unit (decline, defer, or downgrade service when exceeded).
- **Relations**: Connected to **SLA, Pricing Plans, and API Key patterns**.

---

## 3. Adoption Patterns Overview
The authors identified **six main groups of patterns** (see Figure 1 in the paper):

1. **Rate Limit Configuration**
   - *Static Rate Limit*: Fixed values, simple and predictable but inflexible.
   - *Dynamic Rate Limit*: Adaptive to traffic/system load, fairer but complex and unpredictable.

2. **Configuration Metrics**
   - *Request-based*: Limits per number of requests.
   - *Time-based*: Limits based on request processing time.
   - *Point-based*: Assign complexity-based points (e.g., GitHub GraphQL API).

3. **Rate Limit Documentation**
   - *Natural Language Documentation*: Human-friendly but inconsistent.
   - *Machine-Readable Documentation*: Enables automation (OpenAPI, OAS) but rarely adopted.

4. **Rate Limit Communication**
   - *Counter Headers*: Real-time feedback via HTTP headers (e.g., GitHub, Shopify).
   - *Reporting Endpoints*: Dedicated endpoints for retrieving quota/usage (e.g., GitHub, Spotify, OpenWeather).

5. **Reaction to Exceeding Limits**
   - *Termination*: Block via IP, user account, or API key.
   - *Mitigation*: Throttling, queuing, or retry with backoff instead of outright blocking.

6. **Granularity**
   - *Client-Level*: Based on IP, user account, or API key.
   - *Resource-Level*: Limits applied at API, endpoint, entity, or provider level.

7. **Server-Side Implementation**
   - *Internal Rate Limiter*: Built into backend, service mesh, or API gateway.
   - *External Rate Limiter*: Outsourced to third-party/cloud services (e.g., AWS, Azure, Cloudflare).
   - *Scope*: 
     - *Global*: Uniform limits across system (Envoy, NGINX ingress, Redis).
     - *Local*: Service- or endpoint-specific limits.

---

## 4. API Gateway and Microservices Context
- **API Gateways** (e.g., Kong, Tyk) provide built-in rate-limiting features.
- **Service Mesh** (e.g., Istio, Envoy) enables distributed rate limiting via sidecar proxies or control planes.
- **Hybrid approaches** combine internal (fine-grained control) and external (scalability, compliance) limiters.

---

## 5. Key Insights for Thesis
- Rate limiting is essential in **API Gateway design** for:
  - Protecting backend resources
  - Enforcing **SLAs and pricing tiers**
  - Defending against **DoS/DDoS attacks**
- **Trade-offs**: Simplicity vs. adaptability, predictability vs. fairness, performance vs. complexity.
- **Challenges**:
  - Lack of standardization in documentation.
  - Limited support for machine-readable limit configs in cloud API gateways.
- **Best Practices**:
  - Combine **headers + reporting endpoints** for communication.
  - Use **dynamic limits with backoff** in high-load scenarios.
  - Apply **granularity carefully** (per API key, endpoint, or entity).
  - Consider **global + local limiters** for robust microservice governance.

---

## 6. Conclusion
The paper provides a **pattern language** for API Rate Limit adoption, showing how gateways and service providers can systematically apply rate limiting. It emphasizes **developer awareness**, **transparent documentation**, and **adaptive implementations** as critical to building secure and scalable API ecosystems.

