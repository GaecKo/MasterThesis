# Integration and Unit Testing of the API gateway

The project contains unit and integration testing:
* **Unit tests**: tests individuals components, such as translation function, parsing function, ...
* **Integration tests**: tests the utilization flow: using the Managers, we create backend, devices (and corresponding mockups), authZ, and then a request. Finally, cleanup of the created data 

## Tests structure
```
./
├── README.md
├── java
│   └── edge_control
│       ├── FullFlowIT.java                   # Integration test
│       ├── CleanupEngineTest.java            # Unit tests (examples)
│       ├── CommandTranslationEngineTest.java
│       ├── DeviceConfigTest.java
│       ├── GatewayTokensCryptoTest.java
│       ├── HttpCommandDefinitionTest.java
│       ├── MqttCommandDefinitionTest.java
│       └── PathCompilerTest.java
└── resources                                 # json: body handled by managers, .conf: mosquitto conf file
    ├── create_adapter_http.json
    ├── create_adapter_mqtt.json
    ├── create_backend.json
    ├── create_device_http.json
    ├── create_device_mqtt.json
    └── mosquitto-test.conf
```

## Launching the tests:
> The tests should be launched on the machine with the docker containers running (such as apisix-vm)
* Integration tests:
```bash
# place yourself in the edge-control folder:
# from api_gateway/ :
cd java-plugins/edge-control

# launch the integration tests
mvn clean test -Dtest=FullFlowIT
```

* Unit tests (single test class):
```bash
# place yourself in the edge-control folder:
# from api_gateway/ :
cd java-plugins/edge-control

# example: run a single unit test class
mvn clean test -Dtest=CleanupEngineTest
```

* Unit tests (multiple test classes):
```bash
# place yourself in the edge-control folder:
# from api_gateway/ :
cd java-plugins/edge-control

# example: run multiple unit test classes
mvn clean test -Dtest=HttpCommandDefinitionTest,MqttCommandDefinitionTest
```

* Run all tests:
```bash
# place yourself in the edge-control folder:
# from api_gateway/ :
cd java-plugins/edge-control

# run the full test suite
mvn clean test
```

## Important Note

Utilization testing is hard to put in place: it requires mockups of http / mqtt devices, mongo database, etc. The version between the testing dependencies (testcontainers, ...) are hard to keep watch off and may fail at some point.
