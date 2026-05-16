# Integration and Unit Testing of the API gateway

The project contains unit and integration testing:
* **Unit tests**: tests individuals components, such as translation function, parsing function, ...
* **Integration tests**: tests the utilization flow: using the Managers, we create backend, devices (and corresponding mockups), authZ, and then a request. Finally, cleanup of the created data 

## Tests structure
% TO UPDATE
```
./
├── README.md
├── java
│   └── edge_control
│       └── FullFlowIT.java         # Integration test 
└── resources                       # json: body handled by managers, .conf: mosquitto conf file
    ├── create_adapter_http.json
    ├── create_adapter_mqtt.json
    ├── create_backend.json
    ├── create_device_http.json
    ├── create_device_mqtt.json
    └── mosquitto-test.conf
```

## Launching the tests:
% TO UPDATE WITH UNTI TEST TUTO
> The tests should be launched on the machine with the docker containers running (such as apisix-vm)
* Integration tests: 
```bash
# place yourself in the edge-control folder:
# from api_gateway/ :
cd java-plugins/edge-control

# launch the integration tests
mvn clean test -Dtest=FullFlowIT`
```

## Important Note

Utilization testing is hard to put in place: it requires mockups of http / mqtt devices, mongo database, etc. The version between the testing dependencies (testcontainers, ...) are hard to keep watch off and may fail at some point. 
