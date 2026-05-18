# TFE26-316: Secure, trustworthy, and interoperable API gateways for heterogeneous digital assets in renewable energy environments

> **Supervisor EPL/INGI:** Pr. Etienne Riviere

> **External supervisor:** Dr. Annanda Rath, SIRRIS research center (Brussels)

> **Students:** Elie Khoury & Arthur De Neyer

> **Keywords**: Cloud computing, Security, Renewable Energy


---

## Documentation Index

### Deployment
- [**API Gateway**](api_gateway/README.md) — Deployment modes, launchings scripts, Docker stack setup, TLS, configuration, ports
- [**Devices**](devices/README.md) — Simulated HTTP & MQTT IoT devices
- [**Backend**](backend/README.md) — Simulated HTTP backend
### API exposed routes
- [**API Routes**](api_gateway/API-ROUTES.md) — All exposed endpoints, authentication, plugins, request schemas

### Plugin
- [**Edge Control (Java Plugin)**](api_gateway/java-plugins/edge-control/README.md) — Auth, translation, onboarding, backend forwarding logic

### Testing
 - [**Integration and Unit tests**](api_gateway/java-plugins/edge-control/src/test/README.md) — Integration and Unit tests information
- [**Perf testing**](testing/final_plan.md) — Performance testing information
---


The goal is to develop a security solution and a piece of software that can be deployed easily on a device close to the edge. This device acts as a smart gateway (API gateway) connecting different types of renewable digital assets, supporting different operating systems, firmware, communication technologies, messaging protocols, data formats, and security algorithms to the cloud backend. 


This software securely manages renewable digital asset onboarding and performs access and usage control of data coming into and leaving the energy generation environment. 


Different APIs need to be developed to allow smart gateways and heterogeneous renewable digital assets to communicate with the backend system. 


The envisaged smart API gateway software must be modular, configurable, interoperable, and communication technology agnostic.

 

A possible work plan would be as follows.

1. Develop a comprehensive reference architecture of smart API containing all the abovementioned properties. 
2. Develop an appropriate access control model for securing both access to/configuration of the API gateway software and data flow through the API gateway. Secure an end-to-end solution for data exchange between end devices and cloud backend. 
3. Build a smart API gateway prototype. 
    * Renewable devices management, including secure end/edge devices onboarding in the energy grid environment.
    * Define and implement a secure communication protocol between the end/edge device and cloud-backend
    * Define access and usage control models and policies for data circulating in the smart API gateway environment, including end/edge device data. 
    Build a testbed, a company (3E) can provide some devices for testing.  

 

> **Note**: This work is part of an ITEA4 project (https://itea4.org/project/homepot.html) funded by Innoviris (for Belgium consortium). In this project, Sirris is collaborating with 3E (https://www.3e.eu/) under energy grid use case. This project is about “Homogenous Cyber Management of Endpoints and OT”. 

> The student(s) will be expected to collaborate with Sirris and the project partners. They may have to visit the partners in Brussels periodically (trips covered).

> Sirris is a non-profit and industry-driven knowledge center founded by Agoria, the federation of the Belgian technology industry. Sirris helps Belgian companies in the implementation of technological innovations, by providing companies with technological advice, by supporting companies in the definition and realization of innovation projects, and by setting up and participating in European R&D projects. Sirris is active in several domains, among which Software Engineering and ICT.

> Sirris and the Cloud and Large-Scale Computing labs collaborated successfully in the past including for similar joint TFE projects.