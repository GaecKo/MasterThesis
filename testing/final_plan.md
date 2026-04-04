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
| Virtual Users | 
| :-----------: | 
| [1: 400]      | 

## Load related
| Packet size     |
| :-------------: |
| {Medium, Large} |

### Architecture related 
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

| CPUs | Virtual Users  | Packet size  | Device Mockup |
| :--: | :------------: | :----------: | :-----------: |
| 2    | {1, 5, 25, 50} | Medium       | F             |

> **Common metrics of this scenario**
* Latency (ms): mean, pX with X = {50, 90, 95, 99, 99.9}, std dev (standard deviation) / Variance
* Throughput (req/s): Achieved request per second
* System metrics: RAM usage, CPU usage 

#### Scenario A: No API Gateway 
Direct communication between a backend and a device (will basically show the forgery and network latency)

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| F           | F              | F                   | F               | F                   | T    (partial)   |


### Scenario B: API Gateway without Plugin
API Gateway simply upstreams request to devices 

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | F              | F                   | F               | F                   | T                |

### Scenario C: API Gateway with Plugin 
API Gateway forwards request to the plugin, which doesn't do a lot, to then give back to API Gateway which upstreams to devices

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | F                   | F               | F                   | T                |

### Scenario D: API Gateway with Plugin which does the request
The API Gateway forwards request to the plugin, which has its whole flow and does itself the request to the device

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | T                   | F               | F                   | T                |

### Scenario E1: API Gateway with Plugin Auth only
The API Gateway forwards request to the plugin, which verifies AuthN/AuthZ, and then does the request to the device (no translation)

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | T                   | T               | F                   | T                |

### Scenario E2: API Gateway with Plugin Translation only
The API Gateway forwards request to the plugin, which translates the reuest and then does the request to the device (no auth)

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | T                   | F               | T                   | T                |

### Scenario F (Final): API Gateway with full Plugin
The API Gateway forwards request to the plugin, which verifies AuthN/AuthZ, then translates the request, and then does the request to the device 

| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | T                   | T               | T                   | T                |

### Extra scenario 1: per APISIX component time analysis
Indicate the time taken per each component
> Extra metric: Component timing: ms per component 
| API Gateway | Plugin Enabled | Plugin does request | AuthN/Z Enabled | Translation Enabled | Security Enabled |
| :---------: | :------------: | :-----------------: | :-------------: | :-----------------: | :--------------: |
| T           | T              | T                   | T               | T                   | T                |

## Context 2: Overload testing
In this context, we want to see how our full architecture reacts to overloading. We test that with all components enabled, as the system would be in real context

> **Common factors of this scenario**:

> Varying factors for all scenarios
| CPUs      | Virtual Users  | Packet size    | 
| :-------: | :------------: | :------------: | 
| {1, 2, 4} | Peak mechanism | {Medium, High} | 

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

## Extra context: Translation complexity analysis
Test temporal and spatial complexity of translation 

