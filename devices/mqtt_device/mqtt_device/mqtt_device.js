'use strict';

require('dotenv').config();
const mqtt = require('mqtt');

// ── Config ────────────────────────────────────────────────────────────────────
const DEVICE_ID   = process.env.DEVICE_ID  || '2';
const BROKER_URL  = process.env.BROKER_URL || 'mqtt://127.0.0.1:1883';
const INTERVAL_MS = parseInt(process.env.INTERVAL_MS || '5000', 10);

const TOPICS = {
  telemetry : `devices/${DEVICE_ID}/telemetry`,
  status    : `devices/${DEVICE_ID}/status`,
  commands  : `devices/${DEVICE_ID}/commands`,
};

// ── Helpers ───────────────────────────────────────────────────────────────────
const now  = () => new Date().toISOString();
const rand = (min, max) => parseFloat((Math.random() * (max - min) + min).toFixed(1));

const info  = (...a) => console.log(`\x1b[36m[INFO]\x1b[0m`,  ...a);
const ok    = (...a) => console.log(`\x1b[32m[OK]\x1b[0m`,    ...a);
const warn  = (...a) => console.log(`\x1b[33m[WARN]\x1b[0m`,  ...a);
const error = (...a) => console.error(`\x1b[31m[ERROR]\x1b[0m`, ...a);

// ── Periodic telemetry ────────────────────────────────────────────────────────
function publishTelemetry(client) {
  const payload = JSON.stringify({
    deviceId  : DEVICE_ID,
    timestamp : now(),
    type      : 'telemetry',
    payload   : {
      temperature : rand(18, 30),
      humidity    : rand(40, 80),
      status      : 'nominal',
    },
  });
  client.publish(TOPICS.telemetry, payload, { qos: 1 }, (err) => {
    if (err) error('Failed to publish telemetry:', err.message);
    else     info(`→ telemetry published`);
  });
}

// ── MQTT client ───────────────────────────────────────────────────────────────
info('=== MQTT Device Simulator ===');
info(`Device ID : ${DEVICE_ID}`);
info(`Broker    : ${BROKER_URL}`);
info(`Interval  : ${INTERVAL_MS}ms`);

const client = mqtt.connect(BROKER_URL, {
  clientId        : `mqtt-device-${DEVICE_ID}-${Date.now()}`,
  clean           : true,
  reconnectPeriod : 5000,
  connectTimeout  : 10_000,
});

client.on('connect', () => {
  ok(`Connected to broker at ${BROKER_URL}`);

  client.subscribe(TOPICS.commands, { qos: 1 }, (err) => {
    if (err) error('Subscribe failed:', err.message);
    else     ok(`Subscribed to ${TOPICS.commands}`);
  });

  setInterval(() => publishTelemetry(client), INTERVAL_MS);
});

client.on('message', (topic, message) => {
  info(`← message received on ${topic}:`, message.toString());

  let msg;
  try {
    msg = JSON.parse(message.toString());
  } catch {
    warn('Non-JSON message, skipping ack');
    return;
  }

  // Echo correlationId back on responseTopic so the Java adapter can resolve
  // the pending future. If either field is absent, no ack is expected.
  const { correlationId, responseTopic } = msg;
  if (!correlationId || !responseTopic) return;

  const ack = JSON.stringify({
    deviceId      : DEVICE_ID,
    timestamp     : now(),
    type          : 'ack',
    correlationId,
    payload       : { status: 'received' },
  });

  client.publish(responseTopic, ack, { qos: 1 }, (err) => {
    if (err) error('Failed to publish ack:', err.message);
    else     ok(`→ ack sent to ${responseTopic} [correlationId=${correlationId}]`);
  });
});

client.on('reconnect', () => warn('Reconnecting to broker...'));
client.on('offline',   () => warn('Client offline'));
client.on('error',     (err) => error('MQTT error:', err.message));

// ── Graceful shutdown ─────────────────────────────────────────────────────────
process.on('SIGTERM', () => client.end(true, () => process.exit(0)));
process.on('SIGINT',  () => client.end(true, () => process.exit(0)));