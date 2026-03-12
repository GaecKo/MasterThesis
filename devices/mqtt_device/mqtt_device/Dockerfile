FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --only=production

COPY mqtt_device.js ./

# Defaults — all overridable at runtime via --env-file or -e
ENV DEVICE_ID=2
ENV BROKER_URL=mqtt://127.0.0.1:1883
ENV INTERVAL_MS=5000

CMD ["node", "mqtt_device.js"]