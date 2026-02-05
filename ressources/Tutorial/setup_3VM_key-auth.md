# Setup of the 3 VMs and the auth-key plugin

## Step 1: Create the VMs

```
# Create VM for APISIX + etcd
multipass launch --name apisix-vm --mem 2G --disk 10G

# Create VM for Backend
multipass launch --name backend-vm --mem 2G --disk 10G

# Crete VM for device 1
multipass launch --name device1-vm --mem 2G --disk 10G
```

## Step 2: Install Docker on Each VM

```
sudo apt install -y docker.io docker-compose curl
sudo systemctl enable docker --now
```

## Step 3: Copy your project folder to the backend VM 

```
multipass transfer --recursive ./backend backend-vm:
multipass transfer --recursive ./device1 device1-vm:
```

## Step 4: Run Apache APISIX on the apisix-vm VM

```
sudo systemctl status docker
sudo usermod -aG docker $USER
```

Then log out and log back in so your user can run Docker without sudo

```
curl -sL https://run.api7.ai/apisix/quickstart | sh
```

## Step 5: Create the route to the backend

```
curl -i "http://127.0.0.1:9180/apisix/admin/routes/bakend_1" -X PUT -d '
{
  "uri": "/api/v1/*",
  "upstream": {
    "type": "roundrobin",
    "nodes": {
      "BACKEND_VM_IP:8000": 1
    }
  }
}'
```

## Step 6: add key-auth plugin

Run this on your APISIX VM

```curl -i "http://127.0.0.1:9180/apisix/admin/consumers" -X PUT -d '
{
  "username": "device1",
  "plugins": {
    "key-auth": {
      "key": "secret-key"
    }
  }
}'
```

Enable key-auth on the existing route

```
curl -i "http://127.0.0.1:9180/apisix/admin/routes/bakend_1" -X PATCH -d '
{
  "plugins": {
    "key-auth": {}
  }
}'
```


## Step 7: Build and run the container:

```
multipass shell backend-vm
cd ~/backend
docker build -t backend .
docker run -d --name backend --network host backend
```

```
multipass shell device1-vm
cd ~/device1
docker build -t device1 .
docker run -d --name device1 --network host device1
```


## Step 8: send request to the backend through the API

In the device1 VM

```
sudo docker logs -f device1
```

You get this

```
> fake-device1@1.0.0 start
> node device.js

[2025-11-05T15:42:39.272Z] [No API Key] Failed → 401 Unauthorized
[2025-11-05T15:42:44.239Z] [Wrong API Key] Failed → 401 Unauthorized
[2025-11-05T15:42:49.250Z] [Correct API Key] Sent: { device_id: '1', temp: 24 } → 200 Response: { status: 'ok', received: { device_id: '1', temp: 24 } }

......
```