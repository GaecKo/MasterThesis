FROM node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install
COPY http_device.js .
EXPOSE 8000
CMD ["node", "http_device.js"]