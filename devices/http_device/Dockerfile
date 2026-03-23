FROM node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install

# Install ca-certificates package — not present in node:20-slim by default
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY apisix.crt /usr/local/share/ca-certificates/apisix.crt
RUN update-ca-certificates
ENV NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/apisix.crt

COPY device.crt /certs/device.crt
COPY device.key /certs/device.key
ENV CERT_PATH=/certs/device.crt
ENV KEY_PATH=/certs/device.key

COPY http_device.js .

EXPOSE 8000
EXPOSE 8443
CMD ["node", "http_device.js"]