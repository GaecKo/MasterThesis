# Protocol Translation plugin

## Plugin general information
You can find a guide on plugin in [java-plugins/](../java-plugins.md)

## Protocol Translation doc

### Devices management 

The HTTP endpoint `devices/` is for device handling. 

|  Method  |  URL endpoint  |     Handler     | Headers | Params |       body       |         Response          | Action                                                              |
|:--------:|:--------------:|:---------------:|:-------:|:------:|:----------------:|:-------------------------:|:--------------------------------------------------------------------|
|  `GET`   |   `devices/`   | **API Gateway** |    ?    |   /    |        /         | `[device1, device3, ...]` | Retrive a list of installed devices                                 |
|  `POST`  |   `devices/`   | **API Gateway** |    ?    |   /    | `{deviceConfig}` |        `deviceID`         | Create a new device using the deviceConfig, gives back the deviceID |
|  `GET`   | `devices/{id}` | **API Gateway** |    ?    |   /    |        /         |     `{deviceConfig}`      | Retrieve the config of device with ID = `id`                        |
| `DELETE` | `devices/{id}` | **API Gateway** |    ?    |   /    |        /         |    `success` / `error`    | Delete the device with ID = `id`                                    | 

