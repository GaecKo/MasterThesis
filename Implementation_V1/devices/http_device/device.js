const express = require('express');
const app = express();

const HTTP_BROKER_URL = process.env.HTTP_BROKER_URL
const HTTP_BROKER_IP = process.env.HTTP_BROKER_IP
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
        http_broker_ip: HTTP_BROKER_IP,
        http_broker_url: HTTP_BROKER_URL,
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
    console.log(`HTTP Broker URL: ${HTTP_BROKER_URL}`);
    console.log(`Health check: http://${HTTP_DEVICE_IP}:${PORT}/health`);
    console.log("= = = = = = = = = = = = = = = = = = = = = = = = = = =")
});
