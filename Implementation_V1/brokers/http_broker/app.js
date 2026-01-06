// Example Node.js app using APISIX_IP from environment
const express = require('express');
const app = express();

// Get configuration from environment
const APISIX_GATEWAY_URL = process.env.APISIX_GATEWAY_URL || 'http://localhost:9080';
const BROKERS_IP = process.env.BROKERS_IP;
const PORT = process.env.PORT || 8000;

// --- HTTP Setup ---
app.use(express.json()); // Parse JSON bodies

// Health check endpoint
app.get('/health', (req, res) => {
    console.log("health endpoint reached...")
    res.json({
        status: 'healthy',
        http_broker_ip: process.env.BROKERS_IP,
        apisix_gateway: APISIX_GATEWAY_URL,
        timestamp: new Date().toISOString()
    });
});

// API endpoint that shows gateway info
app.get('/api/v1/info', (req, res) => {
    res.json({
        service: 'http-broker-api',
        version: '1.0.0',
        gateway_url: APISIX_GATEWAY_URL,
        accessed_via_gateway: req.headers['x-forwarded-for'] ? true : false,
        client_ip: req.headers['x-forwarded-for'] || req.ip
    });
});



app.listen(PORT, () => {
    console.log("HTTP Broker server: ")
    console.log(`HTTP Broker server running on port ${PORT}`);
    console.log(`APISIX Gateway URL: ${APISIX_GATEWAY_URL}`);
    console.log(`Health check: http://${BROKERS_IP}:${PORT}/health`);
    console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =")
});
