const express = require('express');
const app = express();
const port = 8000;

app.use(express.json()); // Parse JSON bodies

// Route for device data
app.post('/api/v1/data', (req, res) => {
  console.log('Received data:', req.body);
  res.json({ status: 'ok', received: req.body });
});

// Health endpoint
app.get('/health', (req, res) => {
  res.send('Backend is running');
});

app.listen(port, '0.0.0.0', () => {
  console.log(`Backend listening at http://0.0.0.0:${port}`);
});
