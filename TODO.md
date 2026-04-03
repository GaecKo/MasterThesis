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
* [X] Queuing system
* [X] HTTP Communication from end devices
* [X] MQTT Communication from end devices
* [X] HTTPs for cmd onboarding 

## Performance Analysis
> **deadline (scripting)**: 06/04 (Arthur - Elie)
* [X] Basic scenario
* [ ] Review scenario 

> **deadline (tests)**: fin avril (Arthur - Elie)
* [ ] test whole situation 

## Deployment
> **deadline**: 06/04 (Arthur - Elie)
* [X] Deploy on NUC

## Rate limitation
> **deadline**: 06/04 (Elie)
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
* [X] AuthFilter device --> backend communication flow

## Protocol Security 
> **deadline**: 16/03 (Elie)
* [X] end device: HTTPs - TLS
* [X] end device: MQTTs - TLS
* [X] backend: HTTPs 

## Traffic Overload Protection
> **deadline**: 30/03
* [X] Analysis of TOP mechanism
* [X] Choose which one could be applied
* [ ] Implement 

# Report

## State of the art 
> **deadline**: 30/03 (Arthur - Elie)

## APISIX: how it works
> **deadline**: 06/03 (Arthur)

## State of the art: verify 
> **deadline**: 06/03 (Arthur)

## Elie add here what you'll do in the report :) 

