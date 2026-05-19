FROM node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install

# Install ca-certificates package — not present in node:20-slim by default
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# COPY apisix.crt /usr/local/share/ca-certificates/apisix.crt
# RUN update-ca-certificates
# ENV NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/apisix.crt

# COPY backend.crt /certs/backend.crt
# COPY backend.key /certs/backend.key
# ENV CERT_PATH=/certs/backend.crt
# ENV KEY_PATH=/certs/backend.key

COPY backend.js .

EXPOSE 8000
EXPOSE 8443
CMD ["node", "backend.js"]
