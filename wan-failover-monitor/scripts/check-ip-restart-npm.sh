#!/bin/bash
#
# check-ip-restart-npm.sh
#
# Fallback / safety-net script for detecting a public IP change and
# restarting the Nginx Proxy Manager (NPM) container so it picks up the
# new outbound IP correctly after a WAN failover.
#
# NOTE: This was the original approach before the Hubitat "Public IP WAN
# Monitor" driver (see ../drivers/) took over as the primary mechanism.
# The Hubitat driver already handles: IP detection -> Cloudflare DDNS
# update -> Portainer-triggered container restart, all within ~10 seconds
# of a change. This script is kept only as an independent, hub-agnostic
# safety net in case the Hubitat hub itself is unreachable.
#
# If you run both, use a LONG interval here (e.g. every 30 minutes) so it
# doesn't race with the Hubitat driver's ~10 second reaction time. See the
# crontab line at the bottom of this file.

IP_FILE="/var/tmp/last_public_ip"
LOG_FILE="/var/log/npm-ip-restart.log"
CONTAINER_NAME="npm"

CURRENT_IP=$(curl -s https://api.ipify.org)

if [ -z "$CURRENT_IP" ]; then
    echo "$(date): failed to fetch public IP, skipping check" >> "$LOG_FILE"
    exit 1
fi

if [ -f "$IP_FILE" ]; then
    LAST_IP=$(cat "$IP_FILE")
    if [ "$CURRENT_IP" != "$LAST_IP" ]; then
        docker restart "$CONTAINER_NAME"
        echo "$(date): IP changed $LAST_IP -> $CURRENT_IP, restarted $CONTAINER_NAME" >> "$LOG_FILE"
    fi
fi

echo "$CURRENT_IP" > "$IP_FILE"

# --- crontab (fallback / safety-net interval — 30 min recommended) ---
# */30 * * * * /usr/local/bin/check-ip-restart-npm.sh
#
# --- original 2-minute interval used before the Hubitat driver existed ---
# */2 * * * * /usr/local/bin/check-ip-restart-npm.sh
