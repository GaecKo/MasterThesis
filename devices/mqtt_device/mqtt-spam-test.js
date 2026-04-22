// mqtt-spam-test.js
require('dotenv').config();
const mqtt = require('mqtt');

// ── Config ─────────────────────────────────────────────
const BROKER_URL = 'mqtt://10.15.146.150:1883'; // Mosquitto on gateway VM
const TOPIC      = 'devices/test-device/telemetry';
const INTERVAL_MS = 50;  // 50ms between messages (~20 messages/sec)
const NUM_MSGS    = 500; // total messages to send

// ── MQTT Client ───────────────────────────────────────
const client = mqtt.connect(BROKER_URL, {
  clientId: `spam-device-${Date.now()}`,
  clean: true,
});

client.on('connect', () => {
  console.log(`[MQTT] Connected to broker at ${BROKER_URL}`);

  let count = 0;
  const interval = setInterval(() => {
    if (count >= NUM_MSGS) {
      clearInterval(interval);
      console.log(`[MQTT] Finished sending ${NUM_MSGS} messages`);
      client.end();
      return;
    }

    const payload = JSON.stringify({
      timestamp: new Date().toISOString(),
      value: Math.random() * 100,
    });

    client.publish(TOPIC, payload, { qos: 1 }, (err) => {
      if (err) console.error(`[MQTT] Error publishing: ${err.message}`);
      else console.log(`[MQTT] Sent message #${count + 1}`);
    });

    count++;
  }, INTERVAL_MS);
});

client.on('error', (err) => {
  console.error('[MQTT] Connection error:', err.message);
});