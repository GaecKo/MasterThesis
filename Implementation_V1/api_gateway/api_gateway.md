## API Gateway 

### File structure
```
apisix-docker/
├── conf/
│   ├── config.yaml
│   └── apisix.yaml
├── api_gateway.md
├── api_gateway.sh   
├── configure_jwt.sh
├── configure.sh
├── docker-compose.yml
├── Dockerfile
```

## Launch
The script `api_gateway.sh` should be launched from the APISIX VM. 

## UI interface 
The admin UI interface should be accessible at `http://<VM_IP>:9180/ui`

## Test:
1. Admin API works:
```sh
curl -H "X-API-KEY: admin" http://localhost:9180/apisix/admin/routes
# expected: {}
```
2. Traffic can go through
```sh
curl http://localhost:9080 # replace by an unexisting route! 
# expected: 404 error
```