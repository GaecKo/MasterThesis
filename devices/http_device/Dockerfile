FROM node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install
COPY device.js .
EXPOSE 8000
CMD ["node", "device.js"]