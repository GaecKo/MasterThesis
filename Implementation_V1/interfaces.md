# Interfaces
This file documents the `REST API` interfaces, as well as the `MQTT` topics. 


## HTTP context 

### Cloud <-> API Gateway
| Method | URL endpoint | Handler | Headers | Params | body | Response | Action |
| :-----: | :-----: | :-------:| :-------: | :-----------: | :-----------: | :---: | :------ |
| `GET` | `<deviceIP>/info` | **API Gateway** | `{"auth": ...}` | / | / | `{"temp"=X, "humidity"=Y, ...}` | Retrive the metrics information of `<deviceIP>` |
| `POST` | `<deviceIP>/command` | **API Gateway** | `{"auth": ...}` | / | `{"command":<cmd>}` | `{"status": "success"/...}` | Send a command on `<deviceIP>` | 
| `POST` | `info/` | **Cloud server** | `{"auth": ...}` | / | `{"temp"=X, "humidity"=Y, ...}` | / | Endpoint to receive (periodic) info from end devices | 

### API Gateway <-> IoT device 
| Method | URL endpoint | Handler | Headers | Params | body | Response | Action |
| :-----: | :-----: | :-------:| :-------: | :-----------: | :-----------: | :---: | :------ |
| `GET` | `<deviceIP>/info` | **IoT device** | `{"auth": ...}` | / | / | `{"temp"=X, "humidity"=Y, ...}` | Retrive the metrics information of `<deviceIP>` |
| `POST` | `<deviceIP>/command` | **IoT device** | `{"auth": ...}` | / | `{"command":<cmd>}` | `{"status": "success"/...}` | Send a command on `<deviceIP>` | 
| `POST` | `info/` | **API Gateway** | `{"auth": ...}` | / | `{"temp"=X, "humidity"=Y, ...}` | / | Endpoint to receive (periodic) info from end devices | 

## MQTT context
### Cloud <-> API Gateway
Same as for HTTP context

### APi Gateway <-> IoT device
| Channel | Publisher | Subscriber | Payload | Action |
| :-----: | :-----: | :-------:| :-------: | :----------- | 
|  `<deviceIP>/get_info` | **API Gateway** | **IoT device** | / | Publish a retrieve request to retrieve IoT device information of `<deviceIP>` | 
|  `<deviceIP>/info` | **IoT device** | **API Gateway** | `{"temp"=X, "humidity"=Y, ...}` | Publish the device metrics information of `<deviceIP>` | 
|  `<deviceIP>/do_command` | **API Gateway** | **IoT device** | `{"command": "do X"}` | Publish a request to apply a command on IoT device `<deviceIP>` | 
|  `<deviceIP>/command_status` | **IoT device** | **API Gateway** | `{"status": "success"/...}` | Publish a success indicator of command applied on IoT device `<deviceIP>` | 
