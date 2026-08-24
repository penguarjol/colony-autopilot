# Add this entry immediately after /usr/local/bin/server declares SERVICE_MAP.
# The existing host service manager then accepts: server autopilot <action>.
SERVICE_MAP[autopilot]="colony-autopilot.service"
