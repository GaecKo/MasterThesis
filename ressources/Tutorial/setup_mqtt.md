# Setup to integrate the broker in the architecture

## Step 1: Create the 4th VM for the broker

```
multipass launch --name broker-vm --mem 2G --disk 2G
```

## Step 2: Install Mosquitto on broker-vm

```
multipass shell broker-vm
sudo apt update
sudo apt install mosquitto mosquitto-clients -y
sudo systemctl enable mosquitto
sudo systemctl start mosquitto
```

Check it's running:

```
sudo systemctl status mosquitto
```

## Step 3: Allow external MQTT connections

Edit config:

```
sudo nano /etc/mosquitto/mosquitto.conf
```

Add at the end:

```
listener 1883 0.0.0.0
allow_anonymous true
```

Restart:

```
sudo systemctl restart mosquitto
```

Check port open:

```
sudo netstat -tuln | grep 1883
```

## Step 4: Enable Stream Proxy in APISIX

On your APISIX VM:

- Access to a root docker terminal:

```
sudo docker exec -it -u 0 apisix-quickstart /bin/bash
```

- Go to the config folder:

```
cd /usr/local/apisix/conf
nano config.yaml
```

- add in the config.yaml file under apisix and at the same level of router this:

```
apisix:
  proxy_mode: http&stream
  stream_proxy:
    tcp:
      - 9100
```

IMPORTANT TO REMOVE THE SECOND PROXY_MODE:

```
apisix:
  proxy_mode: http&stream
  ...
  proxy_mode: http
```

- Restart APISIX container:

```
docker exec <container-name>> apisix reload
```

## Step 5: Configure MQTT Stream Route in APISIX

On your APISIX VM:

- Access to a root docker terminal:

```
sudo docker exec -it -u 0 apisix-quickstart /bin/bash
```

- Go to the config folder:

```
cd /usr/local/apisix
```

- Get your APISIX admin key:

```
cat conf/config.yaml | grep admin_key -A 3
```

Run this command to add the stream route:

```
curl http://127.0.0.1:9180/apisix/admin/stream_routes/1   -H "X-API-KEY: vdzweWAEFWIpAilXeotqaXaqjqvRxiOe" -X PUT -d '
{
  "plugins": {
    "mqtt-proxy": {
      "protocol_name": "MQTT",
      "protocol_level": 4
    }
  },
  "upstream": {
    "type": "roundrobin",
    "nodes": [
      { "host": "10.15.146.222", "port": 1883, "weight": 1 }
    ]
  }
}'
```

## Step 6: Add a new mqtt device in the device1-vm

```
multipass transfer --recursive ./device_mqtt device1-vm:
```

Then in the device1-vm run this:

```
# Build
docker build -t device-mqtt .

# Run
docker run -d --name device-mqtt --network host device-mqtt
```

Retransfer the new files to the backnd vm:

```
multipass transfer --recursive ./backend backend-vm:
```

Rebuild and run the backend image and container:

```
sudo docker build -t backend .
sudo docker rm -f backend
sudo docker run -d --name backend --network host backend
```