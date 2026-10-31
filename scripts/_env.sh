# Sourced by the other scripts: locate the Android SDK, JDK and tools on Windows (Git Bash), macOS and Linux.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG=com.saathi.app

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1; EXE=".exe"; BAT=".bat" ;;
  *) IS_WINDOWS=0; EXE=""; BAT="" ;;
esac

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" \
                   "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then ANDROID_HOME="$candidate"; break; fi
  done
fi
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "$HOME/.saathi-toolchain/jdk17" "/c/Users/${USERNAME:-}/.saathi-toolchain/jdk17"; do
    if [ -d "$candidate" ]; then JAVA_HOME="$candidate"; break; fi
  done
fi
if [ "$IS_WINDOWS" = 1 ] && command -v cygpath >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then
  ANDROID_HOME="$(cygpath -u "$ANDROID_HOME")"
fi
export ANDROID_HOME JAVA_HOME

ADB="$ANDROID_HOME/platform-tools/adb$EXE"
EMULATOR="$ANDROID_HOME/emulator/emulator$EXE"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager$BAT"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager$BAT"
if [ ! -x "$ADB" ] && command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; fi

require() {
  if [ ! -e "$1" ]; then
    echo "error: $1 not found. Set ANDROID_HOME (currently '${ANDROID_HOME:-unset}')." >&2
    exit 1
  fi
}
