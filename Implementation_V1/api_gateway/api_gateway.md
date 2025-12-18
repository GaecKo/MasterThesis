## API Gateway 

### File structure
```
apisix-docker/
├── conf/
│   ├── config.yaml             # APISIX config file
│   ├── configure.sh
│   └── configure_jwt.sh

│
├── java-plugins/
│   ├── refresh_jar.sh          # refresh jar scripts 
│   ├── protocol-translation/   # ProtocolTranslation plugin
│   │   ├── mvnw
│   │   ├── ... 
│   │   ├── target/             # jar folder
│   │   │   ├── protocol-translation-0.0.1-SNAPSHOT.jar
│   │   │   ├── ...
│   │   ├── ... 
│
├── api_gateway.md              # this file
├── api_gateway.sh              # api_gateway launch scripts (launches containers)
├── docker-compose.yml          # docker compose config 
├── Dockerfile                  # custom APISIX image with Java 17
```

### Configuration file of APISIX
The basic launch configuration file for APISIX is `conf/config.yaml`. This file is mapped as volume in Docker using:
```yml
volumes:
    - ./conf/config.yaml:/usr/local/apisix/conf/config.yaml:ro
```
> This is under the `apisix` service in `docker-compose.yml`
> APISIX will therefore reads its configuration in `/usr/local/apisix/conf/config.yaml`

## Java plugins 
!**Warning**: It is mentioned to use JDK 21 for plugin dev. We currently use JDK 17 on the APISIX container. It doesn't seem to cause any problem but to keep in mind. 

Java-plugins related information are in ![`java_plugins/`](./java-plugins/java-plugins.md). 

To refresh the jar, you can use the `refresh_jar.sh` script from within the `apisix` VM. 

## Launch
The script `api_gateway.sh` should be launched from the APISIX VM. 

## UI interface 
The admin UI interface should be accessible at `http://<VM_IP>:9180/ui`

## Test:
> To test in the APISIX VM, if from outside, replace localhost with VM IP

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