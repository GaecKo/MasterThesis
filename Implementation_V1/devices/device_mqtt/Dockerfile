# Use lightweight Node.js
FROM node:18-alpine

WORKDIR /app

# Copy package.json and your mqtt device script
COPY package*.json ./
COPY device-mqtt.js ./

# Install dependencies
RUN npm install --only=production

# Environment variables (can override on run)
ENV DEVICE_ID=2
ENV APISIX_MQTT_URL="mqtt://10.15.146.23:9100"
ENV INTERVAL_MS=5000

CMD ["node", "device-mqtt.js"]
