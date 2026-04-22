FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --only=production

COPY mqtt_device.js ./

CMD ["node", "mqtt_device.js"]