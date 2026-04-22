const express = require('express');
const app = express();
const APISIX_IP = process.env.APISIX_IP;
const HTTP_DEVICE_IP = process.env.HTTP_DEVICE_IP;
const DEVICE_ID = process.env.DEVICE_ID || 1;
const PORT = 8000;
const INTERVAL = process.env.INTERVAL_MS || 60000


const API_KEY = 'ST2ELyj3eMjwzCwnv2uHb8EODT3NuJ-f_-2VIBQiJU4'; 

function randomTemp() {
    return Math.floor(Math.random() * 10) + 20;
}

// --- HTTP Setup ---
app.use(express.json());

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

// Handle all HTTP methods and all paths
app.all('*', async (req, res) => {
  console.log("[" + new Date().toISOString() + "] " + JSON.stringify(req.body));
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
    const res = await fetch(`https://nuc4-pc.local:9443/backendForward`, {
      method  : 'POST',
      headers : { 'Content-Type': 'application/json', 'apikey': API_KEY },
      body    : JSON.stringify(body),
    });
});

// --- Periodic telemetry push to APISIX ---
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
        console.log(`[telemetry] → sent to ${APISIX_IP}:9080/backendForward — status=${res.status}`);
    } catch (err) {
        console.error(`[telemetry] → failed to reach ${APISIX_IP}:9080/backendForward: ${err.message}`);
    }
}

app.listen(PORT, () => {
    console.log("HTTP Device server: ")
    console.log(`HTTP Device server running on port ${PORT}`);
    console.log(`Health check: http://${HTTP_DEVICE_IP}:${PORT}/health`);
    console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =")
    console.log(`Starting interval task — pushing telemetry to http://${APISIX_IP}:9080/backendForward every 30s`);
    if (APISIX_IP === "" || APISIX_IP == null) {
        console.error("ERROR! APISIX_IP is empty or null... Won't be able to send any telemitry")
    }
    // setInterval(sendTelemetry, INTERVAL);
});