# Usage

This file goes step by step through the deployment and first usage of the entire system.

> **Warning**: The usage is rather simple once setup, but the initial setup (with Postman) can be tricky. Please read carefully all the instructions.

> **Warning**: TLS is disabled by default, as tricky and exhausting to use with self-signed certificates. 

> Experienced a crash during the script run? While it shouldn't normally happen, if you're able to solve the issue you can then relaunch the script, which will stay 

## Main launch
Simply use the script `main.sh` to launch the entire system (including VMs, etc). :
```bash
./main.sh
```
> This script can take quite a while, as it creates multiple VMs, pull large amount of data (download Java, pull images, ...). 

This will create 3 VMs, as well as mount the corresponding folders:
1. `apisix-vm`: where the gateway and java plugin sidecar will run
2. `devices-vm`: where the simulated devices will run 
3. `backend-vm`: where the simulated backend will run 

It will then setup APISIX (using `api_gateway/api_gateway.sh`):
1. Install OpenJDK 21
2. Build the .jar of the plugin 
3. Start the stack (APISIX, etcd, mosquitto, mongo, ...)
4. Configure the routes of APISIX, setup the plugins, etc. 

It will then setup the backend and devices (using `backend/backend.sh`, `devices/http_device.sh` and `devices/mqtt_device.sh`):
1. Launch the backend NodeJS container 
2. Launch the HTTP NodeJS container 
3. Launch the MQTT NodeJS container 

> **Important note** The http and mqtt devices require a device ID and API Key, which will be available only after onboarding. We will therefore need to relaunch these containers later on. 

At the end, you will see the different IPs of the VMs, do save them!
* `APISIX_IP`, `BACKEND_IP`, `DEVICES_IP`

## API usage 
The API Gateway is fully controlled via API calls. As there are many of them, we created a **Postman** collection that you can easily re-use.

Access the following collection: [EdgeControl Usage (Postman)](https://www.postman.com/gaecko8-6910870/workspace/edgecontrol-usage)

Make sure to use it on a local Postman application (otherwise your local IP won't be accessible). To do so:
1. You will need a Postman account
2. You should access the workspace: [EdgeControl Usage (Postman)](https://www.postman.com/gaecko8-6910870/workspace/edgecontrol-usage). This will open a web browser. 

Then, for the 2 Collections ("1. Onboarding mechanism", and "2. Protocol Translation"):
1. Click on 3 little dots next to the collection (when you go over the collection name on the left panel), click on "Fork"
2. Name the fork as you want, but you can keep the same name as the initial collection. 
3. **In addition**, select the "main" environment under the "Environment to fork" selection. For the second collection fork, no need to actually select the environment again, as it was already copied from previous fork. 
3. Finally, click on "Fork Collection"

You can now open the Postman desktop app, and you should see the 2 collections on your main workspace.

You can now fill a few environment variables in your "main" environment (that will be used throughout the requests). 
* `exp`: the value given by the command `echo "exp: $(($(date +%s) + 86400))"`, such as `1779278301`. This will be used for forgery of JWT token
* `APISIX_IP`, `DEVICES_IP`, and `BACKEND_IP`

Throughout the below steps, you will have to fill the environment variable with the response you get. This highly helps to complete the request with the appropriate data. Of course, the use of Postman is optional, one could create its own JWT token, fill the request with appropriate data, and use a basic curl command. The API routes definition is available at [**API Routes**](api_gateway/API-ROUTES.md).

## Onboarding mechanism 
The following part will use the **Collections > 1. Onboarding mechanism** collection. 
### HTTP device
Let's start by creating a http. To do so:
1. Go to 1.a POST device http
2. Make sure to select the environment you just forked, that already contains values for the above mentioned variables. 
3. Click "Send". The request will create a default config that you can see in the "Body" tab. 

You should have a response such as:
```json
{"device-http": {"apiKey": "l-TMJ98IcSQnvPQ86H1OgLnesrWFbxR2lAiFERL1Q_g", "gatewayDeviceId": "device_41619b34-20f4-4b84-9db3-6866c9a6b10e"}}
```
You should now store the `apiKey` as `HTTP_DEVICE_API_KEY` in the environment variable, as well for `gatewayDeviceId` as `HTTP_DEVICE_ID`

This has created a http device entry with 1 command: `setBatteryOperation` (check under "Body" tab).

### MQTT device
Apply the same but with the request in 1.b POST device mqtt, you should get:
```json
{"device-mqtt": {"apiKey": "DTPc-v4Jtku6_qRDbemD_PQ-hfCkrK3Z3UyH6ybkRyg", "gatewayDeviceId": "device_a3922169-f24a-40c5-b57a-9a45f7a3541d"}}
```
Also make sure to store the given `apiKey` and `gatewayDeviceId` under `MQTT_DEVICE_API_KEY` and `MQTT_DEVICE_ID`, respectively. 

This has created a mqtt device entry with 1 command: `setPower` (check under "Body" tab).

### Backend
Go to 2. POST backend, click on "Send". You should get something similar to:
```json
{"message": "Backend created successfully.", "apiKey": "m9q2ZT43Q_CwpfMvCOBMPrSOGzWH00KofQl4j2sjg2k", "gatewayBackendId": "backend_ef7dc058-ad5a-4fda-a475-74a2752b17d2"}
```
Store the `apiKey` and `gatewayBackendId` under `BACKEND_API_KEY` and `BACKEND_IP`, respectively, in your environment variable 

> We now have onboarded 2 devices and a backend! 

### Backend Authorizations
We want the backend to be able to communicate to the 2 devices using their corresponding commands, for which the backend therefore needs to be authorized.
To do so:
1. Go to 3. POST backendAuth. The request is already made for the previously created devices. You should get:
```json
{"status": "success", "message": "Backend authorization entry added successfully."}
```

### Devices Authorizations
Devices, if they want to communicate to backends, need to be authorized. To do so:
1. Go to 4.a POST deviceAuth http, and click on "Send". You should get:
```json
{"status": "success", "message": "Device authorization added successfully."}
```
Do the same with 4.b POST deviceAuth mqtt 

### Commands checkup
To check the commands your backend can use, you can use 5. GET commands. This will use the `BACKEND_API_KEY` in the headers to give a list of commands per device



## Relaunch the devices 
As we now have the IDs and API Keys of the devices, we can relaunch them with these information so that MQTT device is subscribed to the correct topic and that both devices can communicate with backend. To do so:

### HTTP device update

#### Update ID / API Key
1. Go to `devices/http_device/`
2. Open `http_device.sh`
3. Modify following command with according HTTP_DEVICE_ID and HTTP_DEVICE_API_KEY:
```bash
sudo docker run -d \
    --name http-device-app \
    --network host \
    -e HTTP_DEVICE_IP=$DEVICES_IP \
    -e APISIX_IP=$APISIX_IP \
    -e INTERVAL_MS=5000 \
    -e DEVICE_ID=<HTTP_DEVICE_ID> \
    -e API_KEY=<HTTP_DEVICE_API_KEY> \
    http-device-app
```
4. Save your changes - will be synced with device vm automatically


#### Send telemtry
1. Go to `devices/http_device/`
2. open `http_device.js`
3. Uncomment line 101: `// setInterval(sendTelemetry, INTERVAL);`
4. Save your changes - will be synced with device vm automatically

The device will now send telemetry to the gateway, which will forward it to backend

#### Restart container
1. Connect to device VM with `multipass shell devices-vm`
2. Go to directory `cd devices/http_devices/`
3. Relaunch script `./http_device.sh`


### MQTT device update 

1. Go to `devices/mqtt_device/`
2. Open `mqtt_device.sh`
3. Modify following command with according MQTT_DEVICE_ID and MQTT_DEVICE_API_KEY:
```bash
docker run -d \
  --name mqtt-device-app \
  --network host \
  --restart unless-stopped \
  -e DEVICE_ID=<MQTT_DEVICE_ID> \
  -e API_KEY=<HTTP_DEVICE_ID> \
  -e BROKER_URL="mqtt://$APISIX_IP:1883" \
  -e INTERVAL_MS=5000 \
  mqtt-device-app
```
4. Save your changes - will be synced with device vm automatically

#### Send telemtry
1. Go to `devices/mqtt_device/`
2. open `mqtt_device.js`
3. Uncomment line 65: `  // setInterval(() => publishTelemetry(client), INTERVAL_MS);`
4. Save your changes - will be synced with device vm automatically

The device will now send telemetry to the gateway, which will forward it to backend

#### Restart container
1. Connect to device VM with `multipass shell devices-vm`
2. Go to directory `cd devices/mqtt_devices/`
3. Relaunch script `./mqtt_device.sh`


## Protocol translation 
The following part will use the **Collections > 2. Protocol Translation** collection

### HTTP translation config
To specify the http translation config, go to 1. POST device http and click "Send"

This will create a http adapter for the http device. You should simply see:
```json
Device Translation Created
```

### MQTT translation config
To specify the mqtt translation config, go to 2. POST device mqtt and click "Send" 

This will create a mqtt adapter for the mqtt device. You should simply see:
```json
Device Translation Created
```

### Issuing command
You can now issue command to devices:
1. Go to 3.a POST command http
2. Click on "Send"

You should see something like:
```json
{"path":"/v2/schedule/","method":"POST","body":"Command received and processed","timestamp":"2026-05-19T12:22:55.387Z"}
```

With MQTT:
1. Go to 3.b POST command mqtt
2. Click on "Send"

You should get something like:
```json
{"deviceId":"device_a3922169-f24a-40c5-b57a-9a45f7a3541d","timestamp":"2026-05-19T12:49:12.728Z","type":"ack","correlationId":"1c786f71-20a1-434b-b62d-68b2bf486897","payload":{"status":"received"}}
```

## Check communication

That's it! We now have communicated between all parties. 

### Backend received request
The devices are sending telemetry data every 10 seconds each. To check:
1. Connect to backend-vm: `multipass shell backend-vm`
2. Check logs of backend server: `docker logs backend-app`
3. You should see something like:
// WIP 



