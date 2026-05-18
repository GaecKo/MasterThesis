# Final plan for performance measurement

## System parameters
* NUC devices
* NodeJS / MQTT client on end devices
* Ubuntu 

## Factors
The factors we will make vary throughout the context and scenarios

### System related
| CPUs      | 
| :-------: | 
| {1, 2, 4} |

### Network related 
|  Request/sec | 
| :-----------:| 
| [1: 7000]     | 

## Architecture related 
| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Device Mockup | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :-----------: | :--------------: |
| T/F         | T/F            | T/F                 | T/F             | T/F                 | T/F           | T/F              |

## Metrics
The metrics to observe, **can depend on the context / scenario**:
* Latency (ms): mean, pX with X = {50, 90, 95, 99, 99.9}, std dev (standard deviation) / Variance
* Throughput (req/s): Achieved request per second
* Component timing: ms per component 
* System metrics: RAM usage, CPU usage 

## Contexts

### Context 1: latency characterization per architecture
In this context, we want to measure the effect of each architecture component on the latency. We add one "architectural" component at a time, making it easy to see the effect of each component on the performance side

> **Common factors of this scenario**:

| CPUs | Req/sec        |  Device Mockup | Security Enabled    | 
| :--: | :------------: | :-----------: | :--------------:    |
| 4    | 100    | F           | T (no constraining) |

-> Security is not constraining: it is enabled so resulting computation of plugins / firewall are done, but don't have any actual limitations 

> **Common metrics of this scenario**
* Latency (ms): mean, pX with X = {50, 90, 95, 99, 99.9}, std dev (standard deviation) / Variance
* Throughput (req/s): Achieved request per second
* System metrics: RAM usage, CPU usage 

#### Scenario A: No API Gateway 
Direct communication between a backend and a device (will basically show the forgery and network latency)

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | 
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | 
| F           | F              | F                   | F               | F                   | 


### Scenario B: API Gateway without Plugin
API Gateway simply upstreams request to devices 

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | 
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | 
| T           | F              | F                   | F               | F                   | 

### Scenario C: API Gateway with Plugin 
API Gateway forwards request to the plugin, which doesn't do a lot, to then give back to API Gateway which upstreams to devices

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | 
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | 
| T           | T              | F                   | F               | F                   | 

### Scenario D: API Gateway with Plugin who does request
API Gateway forwards request to the plugin, which does the request to the end devices 

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | 
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | 
| T           | T              | T                   | F               | F                   | 

### Scenario E (Final): API Gateway with full Plugin
The API Gateway forwards request to the plugin, which verifies AuthN/AuthZ, then translates the request, and then does the request to the device 

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | 
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | 
| T           | T              | T                   | T               | T                   | 

## Context 2: Overload testing
In this context, we want to see how our full architecture reacts to overloading. We test that with all components enabled, as the system would be in real context

> **Common factors of this scenario**:

> Varying factors for all scenarios

| CPUs      | Virtual Users  | 
| :-------: | :------------: | 
| {1, 2, 4} | Peak mechanism | 

> Fixed factors for all scenarios

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | T                   | T               | T                   | F                |

* Security is disabled for allowing overloading!

> **Common metrics of this scenario**
* Latency (ms): mean, pX with X = {50, 90, 95, 99, 99.9}, std dev (standard deviation) / Variance
* Throughput (req/s): Achieved request per second
* System metrics: RAM usage, CPU usage 

### Scenaro 1: Full Gateway with real communication
We test the full Gateway with real communication to 1 or more devices running on different machines. 

| Device Mockup |
| :-----------: |
| F             |

### Scenario 2: Full Gateway with mocked devices
To remove the overhead of end device performances, we don't actually do the final request to the device, rather we mock a 200 / success result 

| Device Mockup |
| :-----------: |
| T             |


