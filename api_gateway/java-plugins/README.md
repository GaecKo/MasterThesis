# Java plugins integration - documentation 
The integration of the Java plugins isn't the easiest. Here are the steps to make a Java plugin available in APISIX. 

This is a documentation doc only - it isn't necessary to reproduce the steps to use the implemented system, but rather an explanation of how the java plugin is made available to apisix. 

## 1. Build the .jar file of your plugin:
Place yourself in your plugin directory, such as `java-plugins/protocol-translation`, and run:
```sh
./mvnw clean package
```
This will produce a jar file in `java-plugins/protocol-translation/target`, in our case it is:
```
java-plugins/protocol-translation/target/protocol-translation-0.0.1-SNAPSHOT.jar
```
!**Warning**: This will work as long as the `pom.xml` is correctly configured, such as: [pom.xml](#pomxml)

## 2. Make the jar file usable in the docker container
Add the .jar to your container by specifying it in the volumes, in our case it would give:
```yml
- volumes:
    - ...
    - ./java-plugins/protocol-translation/target/protocol-translation-0.0.1-SNAPSHOT.jar:/usr/local/apisix/java-plugins/protocol-translation/target/protocol-translation-0.0.1-SNAPSHOT.jar
```

## 3. Configure `ext-plugin` of APISIX config file
In `conf/config.yaml`, make sure to add this entry:
```yaml
ext-plugin:
  cmd: ['java', '-jar', '-Xmx1g', '-Xms1g', '/path/to/jar'] 
```
`/path/to/jar` must be the path to your jar file, in the container! In our case, it would be: 
```yaml
ext-plugin:
  cmd: ['java', '-jar', '-Xmx1g', '/usr/local/apisix/java-plugins/protocol-translation/target/protocol-translation-0.0.1-SNAPSHOT.jar']
```

You also need to indicate to APISIX the test socket file:
```yaml
ext-plugin:
    - cmd: ...
    - ...
    - path_for_test: /tmp/runner.sock   
```

## Test your plugin:
**From within the apisix VM:**
1. Configure a route with that plugin enabled:
```sh
curl -i http://127.0.0.1:9180/apisix/admin/routes/1  -H 'X-API-KEY: admin' -X PUT -d '
{
    "uri": "/get",
    "plugins": {
        "ext-plugin-pre-req": {
            "conf" : [
                {"name": "ProtocolTranslation", "value": "{\"enable\":\"feature\"}"}
            ]
        }
    },
    "upstream": {
        "type": "roundrobin",
        "nodes": {
            "httpbin.org:80": 1
        }
    }
}'
```
Replace:
* `uri` with the actual route you want to test with
* `X-API-KEY` value with your admin key (we use admin), 
* `name` (in `plugins.ext-plugin-pre-req.conf.name`) by the value your return in `public String name()` method of your class that implements `PluginFilter`. In our case, it is the string `ProtocolTranslation`. 

2. Call that route to check that your plugin is alive. In our case our plugin intercetps (in its `filter` methid) and does `logger.warn("ProtocolTranslation is running");`. We can trigger that using:
```sh 
curl http://127.0.0.1:9080/get 
```
Again:
* `/get` to be replaced by the route on which you applied the plugin

You should get an anwer like:
```sh
{
  "args": {},
  "headers": {
    "Accept": "*/*",
    "Host": "127.0.0.1",
    "User-Agent": "curl/8.5.0",
    "X-Amzn-Trace-Id": "Root=1-694442ae-6670656f0fd2ee625980c741",
    "X-Forwarded-Host": "127.0.0.1"
  },
  "origin": "172.18.0.1, 91.178.146.46",
  "url": "http://127.0.0.1/get"
}
```

And using `docker logs -f apisix`, you should see:
```
         +-------------------------------------------------+
         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |
+--------+-------------------------------------------------+----------------+
|00000000| 03 00 00 1c 0c 00 00 00 00 00 06 00 08 00 04 00 |................|
|00000010| 06 00 00 00 04 00 00 00 04 00 00 00 39 30 38 30 |............9080|
|00000020| 03 00 00 10 0c 00 00 00 00 00 06 00 04 00 00 00 |................|
|00000030| 06 00 00 00                                     |....            |
+--------+-------------------------------------------------+----------------+
, context: ngx.timer
2025/12/18 18:06:38 [warn] 52#52: *34 [lua] init.lua:962: 2025-12-18 18:06:38.184  WARN 55 --- [ntLoopGroup-2-2] c.e.p.ProtocolTranslationFilter          : ProtocolTranslation is running
```
This is the message from ProtocolTranslation :) 

## Extra: java log details
You can configure the details of the logs in `java-plugins/your-plugin/src/main/ressources/application.yaml`:
```yaml
logging:
  level:
    root: debug
```
This setup prints a lot, simply remove the whole `logging` entry to lower logs. However, you won't see event the log message you code from your plugin. 

