#!/bin/bash

# ============================================
# APISIX VM — nftables flood protection
# ============================================

# Create dedicated table
nft add table ip apisix_guard 2>/dev/null || true

# FORWARD chain at priority -10 — runs before Docker's priority 0
nft add chain ip apisix_guard forward \
    { type filter hook forward priority -10 \; policy accept \; } \
    2>/dev/null || true

# Dynamic blacklist — 10 min auto-expiry
nft add set ip apisix_guard blacklist \
    { type ipv4_addr\; timeout 10m\; } \
    2>/dev/null || true

# ----------------------------------------
# Idempotency — only add rules once
# ----------------------------------------
if nft add set ip apisix_guard _init_flag \
    { type ipv4_addr\; } 2>/dev/null; then

    echo "First run — adding rules..."

    # Drop blacklisted IPs first — all ports, all protocols
    nft add rule ip apisix_guard forward \
        ip saddr @blacklist drop

    # HTTP flood (capture new connections to API port 9080)
    nft add rule ip apisix_guard forward \
        ip protocol tcp tcp dport 9080 ct state new \
        meter tcp_flood { ip saddr limit rate over 100/second} \
        add @blacklist { ip saddr } counter drop

    # HTTPS flood (capture new connections to API port 9443)
    nft add rule ip apisix_guard forward \
        ip protocol tcp tcp dport 9443 ct state new \
        meter tcp_flood_tls { ip saddr limit rate over 50/second} \
        add @blacklist { ip saddr } counter drop

    # MQTT plain (capture new + established publishes)
    nft add rule ip apisix_guard forward \
        ip protocol tcp tcp dport 1883 ct state new,established \
        meter mqtt_flood { ip saddr limit rate over 35/second } \
        add @blacklist { ip saddr } counter drop

    # MQTT WebSocket
    nft add rule ip apisix_guard forward \
        ip protocol tcp tcp dport 9001 ct state new,established \
        meter mqttws_flood { ip saddr limit rate over 50/second } \
        add @blacklist { ip saddr } counter drop

    # MQTTS (TLS) if enabled
    nft add rule ip apisix_guard forward \
        ip protocol tcp tcp dport 8883 ct state new,established \
        meter mqtts_flood { ip saddr limit rate over 50/second } \
        add @blacklist { ip saddr } counter drop

    # # UDP flood (example for CoAP)
    # nft add rule ip apisix_guard forward \
    #     ip protocol udp \
    #     meter udp_flood { ip saddr limit rate over 200/second } \
    #     add @blacklist { ip saddr } counter drop

    # # ICMP flood
    # nft add rule ip apisix_guard forward \
    #     ip protocol icmp \
    #     meter icmp_flood { ip saddr limit rate over 100/second } \
    #     add @blacklist { ip saddr } counter drop

    echo "Rules applied successfully."
else
    echo "Already initialized — skipping."
fi



# Apply nftable
# sudo ./nftables-apisix.sh

# Delete the table to reset:
# sudo nft delete table ip apisix_guard

# Test command for HTTP:
    # seq 1 50 | xargs -I{} -P5 curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" \
    #     -X POST "http://10.15.146.150:9080/command" \
    #     -H "Content-Type: application/json" \
    #     -H "X-API-KEY: YOUR_API_KEY" \
    #     -d '{"gatewayDeviceId":"DEVICE_ID","command":"setBatteryOperation","params":{}}'
# Test command for MQTT (using mosquitto_pub):
#     node mqtt-spam-test.js

