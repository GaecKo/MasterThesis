const express = require('express');
const https   = require('https');
const http    = require('http');
const fs      = require('fs');

const app = express();

// ── Config ────────────────────────────────────────────────────────────────────
const APISIX_IP      = process.env.APISIX_IP      || 'nuc4-pc.local';
const HTTP_DEVICE_IP = process.env.HTTP_DEVICE_IP  || 'nuc8-pc.local';
const DEVICE_ID      = process.env.DEVICE_ID       || 'http_device_001';
const API_KEY        = process.env.API_KEY         || '';
const INTERVAL_MS    = parseInt(process.env.INTERVAL_MS || '60000', 10);

// Each device container gets its own HTTPS_PORT so the backend can target them
// on different ports. Set via env var in docker run / launch_devices.sh.
const HTTP_PORT      = parseInt(process.env.HTTP_PORT  || '8000',  10);
const HTTPS_PORT     = parseInt(process.env.HTTPS_PORT || '8443',  10);

const CERT_PATH = process.env.CERT_PATH || '/certs/device.crt';
const KEY_PATH  = process.env.KEY_PATH  || '/certs/device.key';

// ── Helpers ───────────────────────────────────────────────────────────────────
const randomTemp = () => Math.floor(Math.random() * 10) + 20;

// ── Routes ────────────────────────────────────────────────────────────────────
app.use(express.json());

app.get('/health', (req, res) => {
  res.json({
    status:          'healthy',
    deviceId:        DEVICE_ID,
    http_device_ip:  HTTP_DEVICE_IP,
    httpsPort:       HTTPS_PORT,
    timestamp:       new Date().toISOString(),
  });
});

// Catch-all: accept any command, log it, and respond immediately.
// Content doesn't matter for perf testing — we measure transport only.
app.all('*', async (req, res) => {
  console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} — ${JSON.stringify(req.body)}`);
  res.json({
    path:      req.path,
    method:    req.method,
    deviceId:  DEVICE_ID,
    body:      'Command received and processed',
    timestamp: new Date().toISOString(),
  });
});

// ── Telemetry ─────────────────────────────────────────────────────────────────
async function sendTelemetry() {
  const body = {
    deviceId:  DEVICE_ID,
    timestamp: new Date().toISOString(),
    type:      'telemetry',
    payload: {
      temperature: randomTemp(),
      humidity:    Math.floor(Math.random() * 40) + 40,
      status:      'nominal',
    },
  };
  try {
    const res = await fetch(`https://${APISIX_IP}:9443/backendForward`, {
      method:  'POST',
      headers: { 'Content-Type': 'application/json', 'apikey': API_KEY },
      body:    JSON.stringify(body),
    });
    console.log(`[telemetry] → sent — status=${res.status}`);
  } catch (err) {
    console.error(`[telemetry] → failed: ${err.message}`);
  }
}

// ── Startup ───────────────────────────────────────────────────────────────────
console.log('=== HTTP Device Simulator ===');
console.log(`Device ID  : ${DEVICE_ID}`);
console.log(`HTTPS port : ${HTTPS_PORT}`);
console.log(`Gateway    : ${APISIX_IP}`);

http.createServer(app).listen(HTTP_PORT, () => {
  console.log(`HTTP  server listening on port ${HTTP_PORT}`);
});

try {
  const tlsOptions = {
    cert: fs.readFileSync(CERT_PATH),
    key:  fs.readFileSync(KEY_PATH),
  };
  https.createServer(tlsOptions, app).listen(HTTPS_PORT, () => {
    console.log(`HTTPS server listening on port ${HTTPS_PORT}`);
  });
} catch (err) {
  console.error(`[HTTPS] Failed to start — check CERT_PATH/KEY_PATH: ${err.message}`);
  console.warn('[HTTPS] Continuing with HTTP only');
}

if (!APISIX_IP) {
  console.error('ERROR: APISIX_IP is not set — telemetry will fail');
}

// setInterval(sendTelemetry, INTERVAL_MS);