// Example Node.js app using APISIX_IP from environment
const express = require('express');
const app = express();

// Get configuration from environment
const APISIX_GATEWAY_URL = process.env.APISIX_GATEWAY_URL || 'http://localhost:9080';
const BACKEND_IP = process.env.BACKEND_IP;
const PORT = process.env.PORT || 8000;

// --- HTTP Setup ---
app.use(express.json()); // Parse JSON bodies

// Health check endpoint
app.get('/health', (req, res) => {
    console.log("health endpoint reached...")
    res.json({
        status: 'healthy',
        backend_ip: process.env.BACKEND_IP,
        apisix_gateway: APISIX_GATEWAY_URL,
        timestamp: new Date().toISOString()
    });
});

// API endpoint that shows gateway info
app.get('/info', (req, res) => {
    console.log("Received request: " + JSON.stringify(req.body))
    res.json({
        service: 'backend-api',
        version: '1.0.0',
        gateway_url: APISIX_GATEWAY_URL,
        accessed_via_gateway: req.headers['x-forwarded-for'] ? true : false,
        client_ip: req.headers['x-forwarded-for'] || req.ip
    });
});

app.post('/info', (req, res) => {
    console.log("Received request: " + JSON.stringify(req.body))
    res.json({
        service: 'backend-api',
        version: '1.0.0',
        gateway_url: APISIX_GATEWAY_URL,
        accessed_via_gateway: req.headers['x-forwarded-for'] ? true : false,
        client_ip: req.headers['x-forwarded-for'] || req.ip
    });
});



app.listen(PORT, () => {
    console.log("Backend server: ")
    console.log(`Backend server running on port ${PORT}`);
    console.log(`APISIX Gateway URL: ${APISIX_GATEWAY_URL}`);
    console.log(`Health check: http://${BACKEND_IP}:${PORT}/health`);
    console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =")
});
