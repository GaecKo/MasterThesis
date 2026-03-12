# Implementation
## Device Translation Filter
> **deadline**: 16/03 (Arthur)
* [X] DeviceTranslationFilter: finisses `DELETE`/`GET` pour l'onboarding des cmds
* [X] DeviceTranslationFilter: finisse `MQTTAdapter`
* [X] Better corrupted config handling for http command def
* [X] ExceptionHandler used by every filter
* [X] DeviceTranslationFilter: cleaning step of payload
* [X] DeviceTranslationFilter: multithreading 
* [X] Timeout parameter 
* [X] Separate filter in two: cmd onboarding and device translation 

> **deadline**: 23/03 (Arthur)
* [ ] Queuing system
* [ ] HTTP Communication from end devices
* [ ] MQTT Communication from end devices
* [ ] HTTPs for cmd onboarding 

## Performance Analysis
> **deadline (scripting)**: 06/04 (Arthur - Elie)
* [ ] Start creating jupyter notebooks for perf testing 

> **deadline (tests)**: fin avril (Arthur - Elie)
* [ ] test whole situation 

## Deployment
> **deadline**: 06/04 (Arthur - Elie)
* [ ] Deploy on Rasp PI

## Rate limitation
> **deadline**: 06/04 (Arthur)
* [ ] Check how plugin works
* [ ] Check what policies to use
* [ ] ... 

## Onboarding
> **deadline**: 16/03 (Elie)
* [X] JWT and RBAC 
* [X] Routes 
* [X] HTTPs (TLS)
* [X] `GET` methods 
* [X] apply JWT / RBAC for all onboarding route


## Auth Filter
> **deadline**: 09/03 (Elie)
* [X] AuthN AuthZ check mechanism 
* [X] Optimization 
* [ ] AuthFilter device --> backend communication flow

## Protocol Security 
> **deadline**: 16/03 (Elie)
* [ ] end device: HTTPs - TLS
* [ ] end device: MQTTs - TLS
* [ ] backend: HTTPs 

## Traffic Overload Protection
> **deadline**: 30/03
* [ ] Analysis of TOP mechanism
* [ ] Choose which one could be applied
* [ ] Implement 

# Report

## State of the art 
> **deadline**: 30/03 (Arthur - Elie)



