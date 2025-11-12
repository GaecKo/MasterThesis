const express = require('express');
const mqtt = require('mqtt');

const app = express();
const port = 8000;

// --- HTTP Setup ---
app.use(express.json()); // Parse JSON bodies

// Route for device data via HTTP
app.post('/api/v1/data', (req, res) => {
  console.log('Received HTTP data:', req.body);
  res.json({ status: 'ok', received: req.body });
});

// Health endpoint
app.get('/health', (req, res) => {
  res.send('Backend is running');
});

// --- MQTT Setup ---

// --- MQTT Setup 1: Direct Broker (Device → Broker → Backend) ---
const BROKER_MQTT = process.env.BROKER_MQTT || "mqtt://10.15.146.222:1883";
const brokerClient = mqtt.connect(BROKER_MQTT);

brokerClient.on("connect", () => {
  console.log("Backend connected directly to MQTT broker");

  brokerClient.subscribe("device/+/temp", (err) => {
    if (!err) console.log("Subscribed to device temperature topics from broker");
  });
});

brokerClient.on("message", (topic, message) => {
  try {
    const payload = JSON.parse(message.toString());
    console.log(`[Broker → Backend] Received MQTT message: ${topic} ->`, payload);
  } catch (err) {
    console.error("Invalid MQTT message:", message.toString());
  }
});

// --- MQTT Setup 2: APISIX (Backend → APISIX → Broker → Device) ---
const APISIX_MQTT_URL = process.env.APISIX_MQTT_URL || "mqtt://10.15.146.23:9100";
const apisixClient = mqtt.connect(APISIX_MQTT_URL);

const DEVICE_IDS = [2]; // list of device IDs you want to send periodic messages to
const INTERVAL_MS = parseInt(process.env.INTERVAL_MS || 5000); // 5 seconds by default

apisixClient.on("connect", () => {
  console.log("Backend connected to APISIX MQTT proxy");

  // Example: periodic commands/messages to devices
  setInterval(() => {
    DEVICE_IDS.forEach(deviceId => {
      const payload = {
        action: "check_temp",
        timestamp: new Date().toISOString()
      };
      const topic = `device/${deviceId}/commands`;
      apisixClient.publish(topic, JSON.stringify(payload), { qos: 1 }, (err) => {
        if (err) console.error("Failed to send periodic command via APISIX:", err);
        else console.log(`[Backend → APISIX → Device] Periodic command sent to ${topic}:`, payload);
      });
    });
  }, INTERVAL_MS);
});

apisixClient.on("error", (err) => {
  console.error("APISIX MQTT error:", err);
});

// --- Start HTTP server ---
app.listen(port, '0.0.0.0', () => {
  console.log(`Backend listening at http://0.0.0.0:${port}`);
});
