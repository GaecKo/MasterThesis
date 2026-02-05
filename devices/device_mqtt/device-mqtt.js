const mqtt = require("mqtt");

const DEVICE_ID = process.env.DEVICE_ID || 2;
const INTERVAL_MS = parseInt(process.env.INTERVAL_MS || 5000);
const APISIX_MQTT_URL = process.env.APISIX_MQTT_URL || "mqtt://10.15.146.23:9100";

const client = mqtt.connect(APISIX_MQTT_URL);

function randomTemp() {
  return Math.floor(Math.random() * 10) + 20;
}

client.on("connect", () => {
  console.log(`Device ${DEVICE_ID} connected to MQTT broker via APISIX`);

  setInterval(() => {
    const payload = JSON.stringify({
      device_id: DEVICE_ID,
      temp: randomTemp(),
    });
  
    const topic = `device/${DEVICE_ID}/temp`; // <- match backend subscription
    client.publish(topic, payload, { qos: 1 }, (err) => {
      if (err) console.error("Publish failed:", err);
      else console.log(`[${new Date().toISOString()}] Sent to ${topic}:`, payload);
    });
  }, INTERVAL_MS);
  
});

client.on("error", (err) => {
  console.error("MQTT connection error:", err);
});


const BROKER_MQTT = process.env.BROKER_MQTT || "mqtt://10.15.146.222:1883";
const client2 = mqtt.connect(BROKER_MQTT);

const DEVICE_ID2 = process.env.DEVICE_ID || 2;

client2.on("connect", () => {
  console.log(`Device ${DEVICE_ID2} connected directly to MQTT broker`);

  // Subscribe to commands sent from backend via APISIX → Broker
  const commandTopic = `device/${DEVICE_ID2}/commands`;
  client2.subscribe(commandTopic, (err) => {
    if (!err) console.log(`Subscribed to ${commandTopic} for commands`);
  });
});

client2.on("message", (topic, message) => {
  console.log(`Device ${DEVICE_ID2} received message from broker on ${topic}:`, message.toString());
});



