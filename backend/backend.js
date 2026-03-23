const express = require('express');
const https   = require('https');
const fs      = require('fs');
const app     = express();


const BACKEND_IP    = process.env.BACKEND_IP;
const HTTP_PORT     = 8000;
const HTTPS_PORT    = 8443;

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

const tlsOptions = {
  key:  fs.readFileSync('/certs/server.key'),
  cert: fs.readFileSync('/certs/server.crt'),
};

https.createServer(tlsOptions, app).listen(PORT, () => {
  console.log("Backend server:");
  console.log(`Backend server running on HTTPS port ${HTTPS_PORT}, HTTP port ${HTTP_PORT}`);
  console.log(`APISIX Gateway URL: ${APISIX_GATEWAY_URL}`);
  console.log(`Health check: https://${BACKEND_IP}:${HTTPS_PORT}/health`);
  console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =");
});