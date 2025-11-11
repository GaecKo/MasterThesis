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
const APISIX_MQTT = process.env.APISIX_MQTT || "mqtt://10.15.146.23:9100"; // APISIX VM MQTT proxy
const mqttClient = mqtt.connect(APISIX_MQTT);

mqttClient.on("connect", () => {
  console.log("Backend connected to APISIX MQTT proxy");

  // Subscribe to device temperature topics
  mqttClient.subscribe("device/+/temp", (err) => {
    if (!err) console.log("Subscribed to device temperature topics");
    else console.error("Subscription error:", err);
  });
});

mqttClient.on("message", (topic, message) => {
  try {
    const payload = JSON.parse(message.toString());
    console.log(`Received MQTT message: ${topic} ->`, payload);
  } catch (err) {
    console.error("Invalid MQTT message:", message.toString());
  }
});

// Optional: Publish commands to devices
function sendCommand(deviceId, command) {
  const topic = `device/${deviceId}/commands`;
  mqttClient.publish(topic, JSON.stringify(command), { qos: 1 }, (err) => {
    if (err) console.error("Failed to send command:", err);
    else console.log(`Command sent to ${topic}:`, command);
  });
}

app.post('/api/v1/command', (req, res) => {
  const { device_id, command } = req.body;

  if (!device_id || !command) {
    return res.status(400).json({ error: "device_id and command required" });
  }

  sendCommand(device_id, command);
  res.json({ status: 'ok', sent_to: device_id, command });
});



// --- Start HTTP server ---
app.listen(port, '0.0.0.0', () => {
  console.log(`Backend listening at http://0.0.0.0:${port}`);
});
