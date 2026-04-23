FROM eclipse-mosquitto:2.0

# Copy config and certs into the image.
# Place backend.crt and backend.key in this directory before building.
COPY mosquitto.conf /mosquitto/config/mosquitto.conf
COPY backend.crt    /mosquitto/certs/backend.crt
COPY backend.key    /mosquitto/certs/backend.key

# Mosquitto runs as uid 1883 — ensure it can read the certs
RUN chmod 644 /mosquitto/certs/backend.crt \
 && chmod 600 /mosquitto/certs/backend.key \
 && chown -R mosquitto:mosquitto /mosquitto/certs

EXPOSE 8883