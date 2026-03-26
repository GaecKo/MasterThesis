FROM node:18-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --only=production

RUN apk add --no-cache ca-certificates
COPY apisix.crt /usr/local/share/ca-certificates/apisix.crt
RUN update-ca-certificates
ENV NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/apisix.crt

COPY device.crt /certs/device.crt
COPY device.key /certs/device.key
ENV CERT_PATH=/certs/device.crt
ENV KEY_PATH=/certs/device.key

COPY mqtt_device.js ./

CMD ["node", "mqtt_device.js"]