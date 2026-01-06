# Use lightweight Node.js base image
FROM node:18-alpine

# Create app directory
WORKDIR /app

# Copy files
COPY package*.json ./
COPY device.js ./

# Install dependencies
RUN npm install --only=production

# Set environment variables (can be overridden at runtime)
ENV DEVICE_ID=1
ENV APISIX_URL=http://10.15.146.23:9080/api/v1/data
ENV INTERVAL_MS=5000

# Run the app
CMD ["npm", "start"]
