#!/bin/sh
# Installs "Javelin IDE.app" (built by build-app.sh) and the 'javelin' terminal command.
#
#   packaging/macos/install.sh              install
#   packaging/macos/install.sh --uninstall  remove both again
#
# The app goes to /Applications (or ~/Applications if that is not writable). The command goes to /usr/local/bin if
# that exists and is writable, otherwise to ~/.local/bin (add it to your PATH if it is not there already).
set -eu

APP_NAME="Javelin IDE"
here=$(cd "$(dirname "$0")" && pwd -P)
root=$(cd "$here/../.." && pwd -P)

apps_dir() {
  if [ -w /Applications ]; then echo /Applications; else mkdir -p "$HOME/Applications"; echo "$HOME/Applications"; fi
}

bin_dir() {
  if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then echo /usr/local/bin; else mkdir -p "$HOME/.local/bin"; echo "$HOME/.local/bin"; fi
}

if [ "${1-}" = "--uninstall" ]; then
  for apps in /Applications "$HOME/Applications"; do rm -rf "$apps/$APP_NAME.app"; done
  for bin in /usr/local/bin "$HOME/.local/bin"; do rm -f "$bin/javelin"; done
  echo "Removed $APP_NAME and the javelin command. Settings in ~/Library/Application Support/javelin-ide are kept."
  exit 0
fi

built="$root/dist/$APP_NAME.app"
if [ ! -d "$built" ]; then
  echo "Nothing to install: $built does not exist. Run packaging/macos/build-app.sh first." >&2
  exit 1
fi

apps=$(apps_dir)
rm -rf "$apps/$APP_NAME.app"
cp -R "$built" "$apps/"
echo "Installed $apps/$APP_NAME.app"

bin=$(bin_dir)
cp "$here/javelin" "$bin/javelin"
chmod 755 "$bin/javelin"
echo "Installed $bin/javelin"

case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Note: $bin is not on your PATH. Add this line to ~/.zshrc:  export PATH=\"$bin:\$PATH\"" ;;
esac
echo "Try it:  cd some/project && javelin"
