'use strict';

require('dotenv').config();
const mqtt = require('mqtt');

// ── Config ────────────────────────────────────────────────────────────────────
const DEVICE_ID   = process.env.DEVICE_ID   || '2';
const BROKER_URL  = process.env.BROKER_URL  || 'mqtt://127.0.0.1:1883';
const INTERVAL_MS = parseInt(process.env.INTERVAL_MS || '5000', 10);

const TOPICS = {
  telemetry : `devices/${DEVICE_ID}/telemetry`,
  status    : `devices/${DEVICE_ID}/status`,
  commands  : `devices/${DEVICE_ID}/commands`,
};

// ── State ─────────────────────────────────────────────────────────────────────
let startTime      = Date.now();
let intervalMs     = INTERVAL_MS;
let telemetryTimer = null;
let heartbeatTimer = null;

// ── Helpers ───────────────────────────────────────────────────────────────────
const now    = () => new Date().toISOString();
const uptime = () => Math.floor((Date.now() - startTime) / 1000);
const rand   = (min, max) => parseFloat((Math.random() * (max - min) + min).toFixed(1));

const info  = (...a) => console.log(`\x1b[36m[INFO]\x1b[0m`,  ...a);
const ok    = (...a) => console.log(`\x1b[32m[OK]\x1b[0m`,    ...a);
const warn  = (...a) => console.log(`\x1b[33m[WARN]\x1b[0m`,  ...a);
const error = (...a) => console.error(`\x1b[31m[ERROR]\x1b[0m`, ...a);

function buildEnvelope(type, payload, correlationId = null) {
  const envelope = {
    deviceId  : DEVICE_ID,
    timestamp : now(),
    type,
    payload,
  };
  // Echo correlationId back when present so the Java adapter can match the response
  if (correlationId) envelope.correlationId = correlationId;
  return JSON.stringify(envelope);
}

// ── Simulated sensor readings ─────────────────────────────────────────────────
function readTelemetry() {
  return {
    temperature : rand(18, 30),
    humidity    : rand(40, 80),
    status      : 'nominal',
  };
}

// ── Publish helpers ───────────────────────────────────────────────────────────
function publishTelemetry(client) {
  const msg = buildEnvelope('telemetry', readTelemetry());
  client.publish(TOPICS.telemetry, msg, { qos: 1 }, (err) => {
    if (err) error('Failed to publish telemetry:', err.message);
    else     info(`→ telemetry  ${TOPICS.telemetry}`, JSON.parse(msg).payload);
  });
}

function publishStatus(client, state) {
  const msg = buildEnvelope('status', { state, uptime: uptime() });
  client.publish(TOPICS.status, msg, { qos: 1 }, (err) => {
    if (err) error('Failed to publish status:', err.message);
    else     ok(`→ status     ${TOPICS.status}  state=${state}  uptime=${uptime()}s`);
  });
}

/**
 * Publish a command acknowledgement back on the telemetry topic.
 * The correlationId is echoed so the Java adapter can resolve the correct future.
 */
function publishCommandAck(client, correlationId, result) {
  const msg = buildEnvelope('telemetry', result, correlationId);
  client.publish(TOPICS.telemetry, msg, { qos: 1 }, (err) => {
    if (err) error('Failed to publish command ack:', err.message);
    else     ok(`→ ack        ${TOPICS.telemetry}  correlationId=${correlationId}`);
  });
}

// ── Command handler ───────────────────────────────────────────────────────────
function handleCommand(client, raw) {
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch {
    warn('Received non-JSON command, ignoring:', raw);
    return;
  }

  // correlationId must be echoed back in the ack so the Java adapter can
  // match this response to the correct pending CompletableFuture
  const correlationId = msg.correlationId || null;

  // The Java adapter wraps the original HTTP body under msg.payload,
  // so action lives at msg.payload.action
  const cmdPayload    = msg.payload || {};
  const action        = cmdPayload.action;
  const params        = cmdPayload.params || {};

  info(`← command    action=${action}  correlationId=${correlationId}`, params);

  if (!action) {
    warn('Command missing action field — raw payload:', JSON.stringify(cmdPayload));
    if (correlationId) {
      publishCommandAck(client, correlationId, { error: 'Missing action field' });
    }
    return;
  }

  switch (action) {

    case 'ping':
      publishCommandAck(client, correlationId, {
        action : 'ping',
        result : 'pong',
        uptime : uptime(),
      });
      break;

    case 'reboot':
      warn('Reboot command received — simulating restart...');
      publishCommandAck(client, correlationId, {
        action : 'reboot',
        result : 'rebooting',
      });
      publishStatus(client, 'disconnected');
      clearInterval(telemetryTimer);
      clearInterval(heartbeatTimer);
      setTimeout(() => {
        startTime = Date.now();
        publishStatus(client, 'connected');
        startTelemetry(client);
        startHeartbeat(client);
      }, 3000);
      break;

    case 'setInterval':
      if (params.ms && typeof params.ms === 'number' && params.ms >= 1000) {
        intervalMs = params.ms;
        info(`Telemetry interval updated to ${intervalMs}ms`);
        clearInterval(telemetryTimer);
        startTelemetry(client);
        publishCommandAck(client, correlationId, {
          action      : 'setInterval',
          result      : 'ok',
          intervalMs,
        });
      } else {
        warn('setInterval requires params.ms >= 1000');
        publishCommandAck(client, correlationId, {
          action : 'setInterval',
          error  : 'params.ms must be a number >= 1000',
        });
      }
      break;

    default:
      warn(`Unknown command action: "${action}"`);
      publishCommandAck(client, correlationId, {
        action,
        error : `Unknown action: ${action}`,
      });
  }
}

// ── Periodic loops ────────────────────────────────────────────────────────────
function startTelemetry(client) {
  telemetryTimer = setInterval(() => publishTelemetry(client), intervalMs);
}

function startHeartbeat(client) {
  heartbeatTimer = setInterval(() => publishStatus(client, 'heartbeat'), 30_000);
}

// ── MQTT client ───────────────────────────────────────────────────────────────
info('=== MQTT Device Simulator ===');
info(`Device ID  : ${DEVICE_ID}`);
info(`Broker     : ${BROKER_URL}`);
info(`Interval   : ${INTERVAL_MS}ms`);

const client = mqtt.connect(BROKER_URL, {
  clientId        : `mqtt-device-${DEVICE_ID}-${Date.now()}`,
  clean           : true,
  reconnectPeriod : 5000,
  connectTimeout  : 10_000,
  will: {
    topic   : TOPICS.status,
    payload : buildEnvelope('status', { state: 'disconnected', uptime: uptime() }),
    qos     : 1,
    retain  : true,
  },
});

client.on('connect', () => {
  ok(`Connected to broker at ${BROKER_URL}`);

  client.subscribe(TOPICS.commands, { qos: 1 }, (err) => {
    if (err) error('Subscribe failed:', err.message);
    else     ok(`Subscribed to ${TOPICS.commands}`);
  });

  publishStatus(client, 'connected');
  startTelemetry(client);
  startHeartbeat(client);
});

client.on('message', (topic, message) => {
  if (topic === TOPICS.commands) {
    handleCommand(client, message.toString());
  }
});

client.on('reconnect', () => warn('Reconnecting to broker...'));
client.on('offline',   () => warn('Client offline'));
client.on('error',     (err) => error('MQTT error:', err.message));

// ── Graceful shutdown ─────────────────────────────────────────────────────────
function shutdown() {
  info('Shutting down...');
  clearInterval(telemetryTimer);
  clearInterval(heartbeatTimer);
  publishStatus(client, 'disconnected');
  setTimeout(() => client.end(true, () => process.exit(0)), 500);
}

process.on('SIGTERM', shutdown);
process.on('SIGINT',  shutdown);