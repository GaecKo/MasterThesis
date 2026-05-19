const express = require('express');
const https   = require('https');
const http    = require('http');
const fs      = require('fs');

const app = express();

const APISIX_IP     = process.env.APISIX_IP;
const HTTP_DEVICE_IP = process.env.HTTP_DEVICE_IP;
const DEVICE_ID     = process.env.DEVICE_ID || 1;
const HTTP_PORT     = 8000;
const HTTPS_PORT    = 8443; 
const INTERVAL      = process.env.INTERVAL_MS || 60000;
const API_KEY       = process.env.API_KEY || '';

const CERT_PATH = process.env.CERT_PATH || '/certs/device.crt';
const KEY_PATH  = process.env.KEY_PATH  || '/certs/device.key';

// ── Helpers ───────────────────────────────────────────────────────────────────
const now  = () => new Date().toISOString();
const rand = (min, max) => parseFloat((Math.random() * (max - min) + min).toFixed(1));

const info  = (...a) => console.log(`\x1b[36m[INFO]\x1b[0m`,  ...a);
const ok    = (...a) => console.log(`\x1b[32m[OK]\x1b[0m`,    ...a);
const warn  = (...a) => console.log(`\x1b[33m[WARN]\x1b[0m`,  ...a);
const error = (...a) => console.error(`\x1b[31m[ERROR]\x1b[0m`, ...a);

function randomTemp() {
  return Math.floor(Math.random() * 10) + 20;
}

app.use(express.json());

var nb_req = 0

app.get('/health', (req, res) => {
  console.log("health endpoint reached...")
  res.json({
    status: 'healthy',
    http_device_ip: HTTP_DEVICE_IP,
    timestamp: new Date().toISOString()
  });
});

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

app.all('*', async (req, res) => {
  info(`← Message received: ${JSON.stringify(req.body)}`);
  // nb_req ++;
  // console.log("[" + new Date().toISOString() + "] " + nb_req)
  // console.log("Headers: " + JSON.stringify(req.headers))

  res.json({
    path: req.path,
    method: req.method,
    body: "Command received and processed",
    timestamp: new Date().toISOString()
  });
});

async function sendTelemetry() {
  const body = {
    deviceId  : DEVICE_ID,
    timestamp : new Date().toISOString(),
    type      : 'telemetry',
    payload   : {
      temperature : randomTemp(),
      humidity    : Math.floor(Math.random() * 40) + 40,
      status      : 'nominal',
    },
  };
  try {
    const res = await fetch(`http://${APISIX_IP}:9080/backendForward`, {
      method  : 'POST',
      headers : { 'Content-Type': 'application/json', 'apikey': API_KEY },
      body    : JSON.stringify(body),
    });
    info(`→ telemetry sent to ${APISIX_IP}:9443/backendForward — status=${res.status}`);
  } catch (err) {
    error(`→ telemetry failed to reach ${APISIX_IP}:9443/backendForward: ${err}`);
  }
}

// --- Start HTTP ---
http.createServer(app).listen(HTTP_PORT, () => {
  info(`HTTP  server running on port ${HTTP_PORT}, deviceId: ${DEVICE_ID}`);
});

// --- Start HTTPS ---
try {
  const tlsOptions = {
    cert: fs.readFileSync(CERT_PATH),
    key:  fs.readFileSync(KEY_PATH),
  };
  https.createServer(tlsOptions, app).listen(HTTPS_PORT, () => {
    info(`HTTPS server running on port ${HTTPS_PORT}`);
    info(`Health check: https://${HTTP_DEVICE_IP}:${HTTPS_PORT}/health`);
  });
} catch (err) {
  error(`[HTTPS] Failed to start — check CERT_PATH / KEY_PATH: ${err.message}`);
  info("You can ignore this error if you don't need HTTPS.");
  warn(`[HTTPS] Continuing with HTTP only on port ${HTTP_PORT}`);
}

console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =");
if (!APISIX_IP) {
  error("ERROR! APISIX_IP is empty or null... Won't be able to send any telemetry");
}
setInterval(sendTelemetry, INTERVAL);