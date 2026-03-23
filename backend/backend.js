const express = require('express');
const https   = require('https');
const http    = require('http');
const fs      = require('fs');
const app     = express();


const BACKEND_IP    = process.env.BACKEND_IP;
const HTTP_PORT     = 8000;
const HTTPS_PORT    = 8443;

const CERT_PATH = process.env.CERT_PATH || '/certs/backend.crt';
const KEY_PATH  = process.env.KEY_PATH  || '/certs/backend.key';

app.use(express.json());

app.get('/health', (req, res) => {
  console.log("health endpoint reached...")
  res.json({
    status: 'healthy',
    backend_ip: BACKEND_IP,
    timestamp: new Date().toISOString()
  });
});

app.get('/info', (req, res) => {
  console.log("Received request: " + JSON.stringify(req.body))
  res.json({
    service: 'backend-api',
    version: '1.0.0',
    accessed_via_gateway: req.headers['x-forwarded-for'] ? true : false,
    client_ip: req.headers['x-forwarded-for'] || req.ip
  });
});

app.post('/info', (req, res) => {
  console.log("Received request: " + JSON.stringify(req.body))
  res.json({
    service: 'backend-api',
    version: '1.0.0',
    accessed_via_gateway: req.headers['x-forwarded-for'] ? true : false,
    client_ip: req.headers['x-forwarded-for'] || req.ip
  });
});

// --- Start HTTP ---
http.createServer(app).listen(HTTP_PORT, () => {
    console.log("Backend server:");
  console.log(`Backend server running on HTTP port ${HTTP_PORT}`);
  console.log(`Health check: https://${BACKEND_IP}:${HTTP_PORT}/health`);
  console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =");
});

// --- Start HTTPS ---
try {
  const tlsOptions = {
    cert: fs.readFileSync(CERT_PATH),
    key:  fs.readFileSync(KEY_PATH),
  };
  https.createServer(tlsOptions, app).listen(HTTPS_PORT, () => {
    console.log(`HTTPS server running on port ${HTTPS_PORT}`);
    console.log(`Health check: https://${BACKEND_IP}:${HTTPS_PORT}/health`);
  });
} catch (err) {
  console.error(`[HTTPS] Failed to start — check CERT_PATH / KEY_PATH: ${err.message}`);
  console.warn(`[HTTPS] Continuing with HTTP only on port ${HTTP_PORT}`);
}
