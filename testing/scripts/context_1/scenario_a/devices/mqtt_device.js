require('dotenv').config();
const mqtt = require('mqtt');
const fs   = require('fs');

// ── Config ────────────────────────────────────────────────────────────────────
const DEVICE_ID   = process.env.DEVICE_ID   || 'mqtt_device_001';
const BROKER_URL  = process.env.BROKER_URL  || 'mqtts://192.168.50.1:8883';
const INTERVAL_MS = parseInt(process.env.INTERVAL_MS || '60000', 10);
const API_KEY     = process.env.API_KEY     || '';

// CA cert for verifying the Mosquitto broker's TLS certificate.
// Uses backend.crt since the broker now runs on nuc1.
const CA_CERT_PATH = process.env.CA_CERT_PATH || '/certs/backend.crt';

const TOPICS = {
  telemetry : `devices/${DEVICE_ID}/telemetry`,
  status    : `devices/${DEVICE_ID}/status`,
  commands  : `devices/${DEVICE_ID}/commands`,
};

// ── Helpers ───────────────────────────────────────────────────────────────────
const now  = () => new Date().toISOString();
const rand = (min, max) => parseFloat((Math.random() * (max - min) + min).toFixed(1));

const info  = (...a) => console.log('\x1b[36m[INFO]\x1b[0m',  ...a);
const ok    = (...a) => console.log('\x1b[32m[OK]\x1b[0m',    ...a);
const warn  = (...a) => console.log('\x1b[33m[WARN]\x1b[0m',  ...a);
const error = (...a) => console.error('\x1b[31m[ERROR]\x1b[0m', ...a);

// ── TLS options ───────────────────────────────────────────────────────────────
function buildTlsOptions() {
  if (!CA_CERT_PATH) {
    warn('CA_CERT_PATH is empty — TLS certificate verification disabled');
    return { rejectUnauthorized: false };
  }
  try {
    return { ca: fs.readFileSync(CA_CERT_PATH), rejectUnauthorized: true };
  } catch (err) {
    error(`Failed to read CA cert at ${CA_CERT_PATH}: ${err.message}`);
    process.exit(1);
  }
}

// ── Periodic telemetry ────────────────────────────────────────────────────────
function publishTelemetry(client) {
  const payload = JSON.stringify({
    deviceId:  DEVICE_ID,
    timestamp: now(),
    type:      'telemetry',
    apikey:    API_KEY,
    payload: {
      temperature: rand(18, 30),
      humidity:    rand(40, 80),
      status:      'nominal',
    },
  });
  client.publish(TOPICS.telemetry, payload, { qos: 1 }, (err) => {
    if (err) error('Failed to publish telemetry:', err.message);
    else     info('→ telemetry published');
  });
}

// ── MQTT client ───────────────────────────────────────────────────────────────
info('=== MQTT Device Simulator ===');
info(`Device ID : ${DEVICE_ID}`);
info(`Broker    : ${BROKER_URL}`);
info(`Interval  : ${INTERVAL_MS}ms`);

const client = mqtt.connect(BROKER_URL, {
  clientId:        `mqtt-device-${DEVICE_ID}-${Date.now()}`,
  clean:           true,
  reconnectPeriod: 5000,
  connectTimeout:  10_000,
  ...buildTlsOptions(),
});

client.on('connect', () => {
  ok(`Connected to broker at ${BROKER_URL}`);

  client.subscribe(TOPICS.commands, { qos: 1 }, (err) => {
    if (err) error('Subscribe failed:', err.message);
    else     ok(`Subscribed to ${TOPICS.commands}`);
  });

  // setInterval(() => publishTelemetry(client), INTERVAL_MS);
});

client.on('message', (topic, message) => {
  //info(`← message received on ${topic}:`, message.toString());

  let msg;
  try {
    msg = JSON.parse(message.toString());
  } catch {
    warn('Non-JSON message, skipping ack');
    return;
  }

  // Echo correlationId back on responseTopic so the Java adapter (and Locust
  // in scenario A) can match and time the response.
  const { correlationId, responseTopic } = msg;
  if (!correlationId || !responseTopic) return;

  const ack = JSON.stringify({
    deviceId:      DEVICE_ID,
    timestamp:     now(),
    type:          'ack',
    correlationId,
    payload:       { status: 'received' },
  });

  client.publish(responseTopic, ack, { qos: 0 }, (err) => {
    if (err) error('Failed to publish ack:', err.message);
    // else     ok(`→ ack sent to ${responseTopic} [correlationId=${correlationId}]`);
  });
});

client.on('reconnect', () => warn('Reconnecting to broker...'));
client.on('offline',   () => warn('Client offline'));
client.on('error',     (err) => error('MQTT error:', err));

// ── Graceful shutdown ─────────────────────────────────────────────────────────
process.on('SIGTERM', () => client.end(true, () => process.exit(0)));
process.on('SIGINT',  () => client.end(true, () => process.exit(0)));