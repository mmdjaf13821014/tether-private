#!/bin/sh
# Runs the TetherCore tests. With only the Command Line Tools installed, the Swift Testing
# macro plugin must be passed explicitly; with full Xcode plain `swift test` works.
cd "$(dirname "$0")"
PLUGIN=/Library/Developer/CommandLineTools/usr/lib/swift/host/plugins/testing/libTestingMacros.dylib
if [ -f "$PLUGIN" ] && ! xcode-select -p | grep -q Xcode.app; then
  exec swift test -Xswiftc -load-plugin-library -Xswiftc "$PLUGIN" "$@"
fi
exec swift test "$@"
