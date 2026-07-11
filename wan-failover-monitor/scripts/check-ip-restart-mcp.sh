#!/bin/bash
#
# check-ip-restart-mcp.sh
#
# Runs INSIDE the wikijs-mcp LXC container itself (not on the Proxmox host,
# not on the Ubuntu/Docker server). Detects a public IP change and restarts
# either the wikijs-mcp service or the whole container, depending on
# RESTART_MODE below.
#
# Companion to check-ip-restart-npm.sh (used on the Ubuntu/Docker server for
# the NPM container). Both are independent, hub-agnostic safety nets that
# complement the Hubitat "Public IP WAN Monitor" driver, which already
# handles this via Cloudflare DDNS + Portainer/Proxmox API calls. Run this
# with a longer interval (e.g. 30 min) if you keep both.

# --- Configuration ---
IP_FILE="/var/tmp/last_public_ip"
LOG_FILE="/var/log/mcp-ip-restart.log"

# "service" = restart just the wikijs-mcp systemd service (fast, low impact)
# "reboot"  = reboot the whole LXC container (use only if the service restart
#             alone doesn't reliably fix the issue)
RESTART_MODE="service"
SERVICE_NAME="wikijs-mcp"     # adjust to your actual systemd unit name

# --- Logic ---
CURRENT_IP=$(curl -s https://api.ipify.org)

if [ -z "$CURRENT_IP" ]; then
    echo "$(date): failed to fetch public IP, skipping check" >> "$LOG_FILE"
    exit 1
fi

if [ -f "$IP_FILE" ]; then
    LAST_IP=$(cat "$IP_FILE")
    if [ "$CURRENT_IP" != "$LAST_IP" ]; then
        if [ "$RESTART_MODE" = "reboot" ]; then
            echo "$(date): IP changed $LAST_IP -> $CURRENT_IP, rebooting container" >> "$LOG_FILE"
            reboot
        else
            systemctl restart "$SERVICE_NAME"
            echo "$(date): IP changed $LAST_IP -> $CURRENT_IP, restarted service '$SERVICE_NAME'" >> "$LOG_FILE"
        fi
    fi
fi

echo "$CURRENT_IP" > "$IP_FILE"

# --- crontab setup (run as root, since systemctl restart / reboot need it) ---
# sudo crontab -e
#
# Fallback / safety-net interval (recommended if the Hubitat driver is
# also handling this):
# */30 * * * * /usr/local/bin/check-ip-restart-mcp.sh
#
# Standalone interval (if NOT using the Hubitat driver's Proxmox/Portainer
# restart features for this container):
# */2 * * * * /usr/local/bin/check-ip-restart-mcp.sh
