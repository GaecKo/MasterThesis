const express = require('express');
const app = express();

const HTTP_DEVICE_IP = process.env.HTTP_DEVICE_IP
const DEVICE_ID = process.env.DEVICE_ID || 1;
const PORT = 8000

function randomTemp() {
  return Math.floor(Math.random() * 10) + 20;
}

// --- HTTP Setup ---
app.use(express.json()); // Parse JSON bodies

app.get('/health', (req, res) => {
    console.log("health endpoint reached...")
    res.json({
        status: 'healthy',
        http_device_ip: HTTP_DEVICE_IP,
        timestamp: new Date().toISOString()
    });
});

// Handle all HTTP methods and all paths
app.all('*', (req, res) => {
    console.log({
        path: req.path,
        method: req.method,
        body: req.body,
        query: req.query,
        params: req.params,
        headers: req.headers
    });
    
    res.json({
        path: req.path,
        method: req.method,
        body: req.body,
        query: req.query,
        params: req.params,
        url: req.url,
        originalUrl: req.originalUrl,
        timestamp: new Date().toISOString()
    });
});

/*
POST https://{PVMS_DOMAIN}/thirdData/controlDeviceActivePower
Body:
{
 "plantCode": "{plantCode}",
 "deviceCode": "{deviceCode}",
 "mode": "LIMIT",
 "activePower": 5000
}
*/

app.listen(PORT, () => {
    console.log("HTTP Device server: ")
    console.log(`HTTP Device server running on port ${PORT}`);
    console.log(`Health check: http://${HTTP_DEVICE_IP}:${PORT}/health`);
    console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =")
});
