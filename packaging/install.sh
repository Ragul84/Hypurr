#!/bin/sh
# Hypurr installer — curl -fsSL https://raw.githubusercontent.com/Ragul84/Hypurr/main/packaging/install.sh | sh
#
#   macOS: the Hypurr app (the host ships inside it)
#   Linux: hypurr-host, plus the desktop app when a display is present
#
# Options (after `sh -s --`):
#   --host-only   only hypurr-host (headless Macs, servers)
#   --app         Linux: also install the desktop app without a display
# Environment:
#   HYPURR_VERSION  a release tag such as v2.2.1 (default: latest)
#   HYPURR_BIN_DIR  where binaries go (default: ~/.local/bin, /usr/local/bin as root)
set -eu

HOST_ONLY=0
FORCE_APP=0
for arg in "$@"; do
  case $arg in
    --host-only) HOST_ONLY=1 ;;
    --app) FORCE_APP=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

RELEASES=https://github.com/Ragul84/Hypurr/releases
VERSION=${HYPURR_VERSION:-latest}
if [ "$VERSION" = latest ]; then BASE=$RELEASES/latest/download; else BASE=$RELEASES/download/v${VERSION#v}; fi

case $(uname -s) in
  Darwin) OS=macos ;;
  Linux) OS=linux ;;
  *) die "Hypurr supports macOS and Linux." ;;
esac
case $(uname -m) in
  arm64 | aarch64) ARCH=arm64 ;;
  x86_64 | amd64) ARCH=x86_64 ;;
  *) die "Unsupported CPU: $(uname -m) (x86_64 and arm64 only)." ;;
esac

if [ "$(id -u)" = 0 ]; then BIN_DIR=${HYPURR_BIN_DIR:-/usr/local/bin}; else BIN_DIR=${HYPURR_BIN_DIR:-$HOME/.local/bin}; fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Downloads a release asset into $TMP and checks it against its .sha256.
fetch() {
  say "Downloading $1"
  curl -fL --progress-bar -o "$TMP/$1" "$BASE/$1" || die "download failed: $BASE/$1"
  curl -fsSL -o "$TMP/$1.sha256" "$BASE/$1.sha256" || die "checksum missing: $BASE/$1.sha256"
  want=$(cut -d' ' -f1 "$TMP/$1.sha256")
  if command -v sha256sum >/dev/null; then got=$(sha256sum "$TMP/$1" | cut -d' ' -f1); else got=$(shasum -a 256 "$TMP/$1" | cut -d' ' -f1); fi
  [ "$want" = "$got" ] || die "checksum mismatch for $1"
}

# Replaces $2 with $1 by rename, so a running binary is never overwritten in place.
place() {
  mkdir -p "$(dirname "$2")"
  cp "$1" "$2.new" && chmod 755 "$2.new" && mv -f "$2.new" "$2"
}

install_host() {
  name=hypurr-host-$OS-$ARCH
  fetch "$name.tar.gz"
  tar xzf "$TMP/$name.tar.gz" -C "$TMP"
  place "$TMP/$name/hypurr-host" "$BIN_DIR/hypurr-host"
  say "Installed $("$BIN_DIR/hypurr-host" --version) to $BIN_DIR"
  restart_service "$BIN_DIR/hypurr-host"
}

# Restarts the background host if its service runs the binary at $1.
restart_service() {
  if [ $OS = linux ]; then
    unit=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/hypurr-host.service
    grep -qF "$1" "$unit" 2>/dev/null && systemctl --user restart hypurr-host && say "Restarted the hypurr-host service"
  else
    grep -qF "$1" "$HOME/Library/LaunchAgents/com.ragul84.hypurr.host.plist" 2>/dev/null &&
      launchctl kickstart -k "gui/$(id -u)/com.ragul84.hypurr.host" && say "Restarted the hypurr-host service"
  fi
  return 0
}

install_mac_app() {
  fetch hypurr-macos.dmg
  mnt=$TMP/mnt
  hdiutil attach -quiet -nobrowse -readonly -mountpoint "$mnt" "$TMP/hypurr-macos.dmg"
  osascript -e 'tell application id "com.ragul84.Hypurr" to quit' >/dev/null 2>&1 || true
  sudo=""
  [ -w /Applications ] || sudo=sudo
  $sudo rm -rf /Applications/Hypurr.app
  $sudo ditto "$mnt/Hypurr.app" /Applications/Hypurr.app
  hdiutil detach -quiet "$mnt"
  mkdir -p "$BIN_DIR" && ln -sf /Applications/Hypurr.app/Contents/MacOS/hypurr-host "$BIN_DIR/hypurr-host"
  say "Installed Hypurr.app to /Applications"
  restart_service /Applications/Hypurr.app/Contents/MacOS/hypurr-host
  open /Applications/Hypurr.app
}

install_linux_app() {
  name=hypurr-linux-$ARCH
  fetch "$name.tar.gz"
  tar xzf "$TMP/$name.tar.gz" -C "$TMP"
  place "$TMP/$name/bin/hypurr" "$BIN_DIR/hypurr"
  data=${XDG_DATA_HOME:-$HOME/.local/share}
  [ "$(id -u)" = 0 ] && data=/usr/local/share
  mkdir -p "$data" && cp -R "$TMP/$name/share/." "$data/"
  say "Installed the Hypurr desktop app to $BIN_DIR/hypurr"
  if command -v ldd >/dev/null && missing=$(ldd "$BIN_DIR/hypurr" 2>&1 | grep 'not found'); then
    echo "   It won't start yet: it needs GTK 4, libadwaita, VTE (GTK 4) and glibc 2.39+ (Ubuntu 24.04 or newer). Missing:"
    echo "$missing" | sed 's/^[[:space:]]*/     /' | sort -u
  fi
}

if [ $OS = macos ] && [ $HOST_ONLY = 0 ]; then
  install_mac_app
  echo
  echo "Hypurr is open and sets up the host on its own. Pair your iPhone from the menu bar: Pair iPhone…"
else
  install_host
  if [ $OS = linux ] && [ $HOST_ONLY = 0 ] && { [ $FORCE_APP = 1 ] || [ -n "${WAYLAND_DISPLAY:-}${DISPLAY:-}" ]; }; then
    install_linux_app
  fi
  echo
  echo "Next:"
  echo "  hypurr-host install   # run in the background (again after upgrading from another path)"
  echo "  hypurr-host pair      # QR code for the iPhone"
fi

case :$PATH: in
  *:"$BIN_DIR":*) ;;
  *) echo; echo "Add $BIN_DIR to your PATH: export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
