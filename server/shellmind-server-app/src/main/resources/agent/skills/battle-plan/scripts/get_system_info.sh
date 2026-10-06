#!/bin/bash

# Script name: get_system_info.sh
# Description: Collect macOS system configuration information
# Author: Trae AI

echo "================================================"
echo "           System configuration overview"
echo "================================================"

# 1. Hostname
echo "[Host information]"
echo "  Hostname    : $(hostname)"
echo "  Username    : $(whoami)"
echo ""

# 2. Operating system version
echo "[Operating system]"
PRODUCT_NAME=$(sw_vers -productName)
PRODUCT_VERSION=$(sw_vers -productVersion)
BUILD_VERSION=$(sw_vers -buildVersion)
echo "  OS name     : $PRODUCT_NAME"
echo "  OS version  : $PRODUCT_VERSION (Build $BUILD_VERSION)"
# Kernel version
echo "  Kernel      : $(uname -r)"
echo ""

# 3. CPU information
echo "[CPU information]"
CPU_BRAND=$(sysctl -n machdep.cpu.brand_string)
PHY_CORES=$(sysctl -n hw.physicalcpu)
LOG_CORES=$(sysctl -n hw.logicalcpu)
echo "  Model       : $CPU_BRAND"
echo "  Physical cores : $PHY_CORES"
echo "  Logical cores  : $LOG_CORES"
# Try to get architecture (e.g. x86_64 or arm64)
ARCH=$(uname -m)
echo "  Architecture   : $ARCH"
echo ""

# 4. Memory information
echo "[Memory information]"
MEM_BYTES=$(sysctl -n hw.memsize)
MEM_GB=$(echo "scale=2; $MEM_BYTES / 1024 / 1024 / 1024" | bc)
echo "  Total memory   : ${MEM_GB} GB"
echo ""

# 5. Disk usage (root)
echo "[Disk information (root)]"
# Use df -h for the root filesystem and format the output
df -h / | awk 'NR==2 {printf "  Total capacity : %s\n  Used           : %s\n  Available      : %s\n  Usage          : %s\n", $2, $3, $4, $5}'
echo ""

# 6. Network information
echo "[Network information]"
# Default interface IP (usually en0 Wi-Fi or en1)
IP_ADDR=$(ipconfig getifaddr en0)
if [ -z "$IP_ADDR" ]; then
    IP_ADDR=$(ipconfig getifaddr en1)
fi

if [ -z "$IP_ADDR" ]; then
    echo "  IP address    : not connected or unavailable"
else
    echo "  IP address    : $IP_ADDR"
fi
echo ""

echo "================================================"
echo "Information collection complete."
