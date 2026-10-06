#!/bin/bash

# check_cleanable_files.sh
# Purpose: list common junk files and cleanable content on macOS, including occupied space.
# Note: this script only scans and lists; it does not delete any files.

echo "============================================================"
echo "               macOS cleanable junk-file scan                 "
echo "============================================================"
echo "Scanning, please wait..."
echo ""

# Define colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function: check and print directory size
check_dir_size() {
    local name="$1"
    local path="$2"
    local desc="$3"

    if [ -d "$path" ]; then
        # Use du -sh to compute size and extract the value
        # 2>/dev/null suppresses permission errors
        size=$(du -sh "$path" 2>/dev/null | cut -f1)
        
        # Empty or unreadable directories may show 0B or empty
        if [ -n "$size" ]; then
            echo -e "${YELLOW}[$name]${NC}"
            echo -e "  Path: $path"
            echo -e "  Size: ${RED}$size${NC}"
            echo -e "  Notes: $desc"
            echo "------------------------------------------------------------"
        fi
    fi
}

# 1. User caches
check_dir_size "User caches" "$HOME/Library/Caches" "Temporary files produced by applications. Usually safe to clean (apps may reload data more slowly afterward)."

# 2. System logs
check_dir_size "User logs" "$HOME/Library/Logs" "Application log files. Usually safe to clean if you do not need them for troubleshooting."
check_dir_size "System logs" "/private/var/log" "System runtime logs, usually managed automatically. Old logs can be cleaned if they pile up."

# 3. Trash
check_dir_size "Trash" "$HOME/.Trash" "Files that were deleted but not emptied."

# 4. Xcode development junk (if present)
check_dir_size "Xcode DerivedData" "$HOME/Library/Developer/Xcode/DerivedData" "Intermediate files and indexes from Xcode builds. They are regenerated on the next compile (deleting them can also fix many Xcode errors)."
check_dir_size "Xcode iOS DeviceSupport" "$HOME/Library/Developer/Xcode/iOS DeviceSupport" "Support files for iOS devices you have connected. Safe to clean if you no longer debug old iOS versions."
check_dir_size "Xcode Archives" "$HOME/Library/Developer/Xcode/Archives" "Archived App packages. Safe to clean if you no longer need old builds."

# 5. Browser caches (examples)
check_dir_size "Chrome cache" "$HOME/Library/Caches/Google/Chrome" "Chrome browser cache files."
# Firefox
check_dir_size "Firefox cache" "$HOME/Library/Caches/Firefox" "Firefox browser cache files."

# 6. Package-manager caches
# Homebrew
if command -v brew &> /dev/null; then
    brew_cache=$(brew --cache)
    check_dir_size "Homebrew cache" "$brew_cache" "Homebrew downloaded package cache. Clean with 'brew cleanup'."
fi

# 7. Language environment caches/dependencies
check_dir_size "Yarn cache" "$HOME/Library/Caches/Yarn" "Yarn package-manager cache."
check_dir_size "npm cache" "$HOME/.npm" "npm package-manager cache."
check_dir_size "Maven cache" "$HOME/.m2/repository" "Maven repository. Not junk, but it can take a lot of space if unused for a long time."
check_dir_size "Gradle cache" "$HOME/.gradle/caches" "Gradle build cache."
check_dir_size "CocoaPods cache" "$HOME/Library/Caches/CocoaPods" "CocoaPods dependency cache."

# 8. Docker (if installed)
if command -v docker &> /dev/null; then
    echo -e "${YELLOW}[Docker unused resources]${NC}"
    echo -e "  Notes: stopped containers, unused images, and unused networks."
    echo -e "  Suggested command: ${GREEN}docker system df${NC} for details"
    # docker system df may require Docker to be running
    if docker info &> /dev/null; then
        docker system df
    else
        echo "  (Docker is not running; size unavailable)"
    fi
    echo "------------------------------------------------------------"
fi

# 9. Downloads folder (reminder)
check_dir_size "Downloads folder" "$HOME/Downloads" "Downloaded files, often including unused installers and temporary files."

echo ""
echo "============================================================"
echo "Suggested cleanup:"
echo "1. Use 'rm -rf <path>' to delete a specific directory (double-check the path)."
echo "2. For Homebrew, use 'brew cleanup'."
echo "3. For Docker, use 'docker system prune'."
echo "4. For Xcode, you can delete the DerivedData directory directly."
echo "5. Dedicated cleaners (such as CleanMyMac or Tencent Lemon) are recommended for safer cleanup."
echo "============================================================"
