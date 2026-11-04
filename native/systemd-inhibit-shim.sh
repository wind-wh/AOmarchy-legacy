#!/bin/sh
# /usr/local/bin/systemd-inhibit for Android/Andromarchy: there is no logind, so there is nothing to ask for
# an inhibitor. The real binary in /usr/bin talks to org.freedesktop.login1 over D-Bus and fails; Omarchy's
# power menu wraps "shutdown" in `systemd-inhibit ... omarchy-system shutdown --inhibited`, so that failure
# stopped the shutdown halfway and only produced the "shutdown failed" notification.
# Run the wrapped command, pass its exit status through, and claim no inhibitors.
if [ "$1" = "--list" ] || [ "$1" = "--list-details" ]; then
  echo "No inhibitors."
  exit 0
fi
while [ "$#" -gt 0 ]; do
  case "$1" in
    -*) shift ;; # --what=... --who=... --why=... --mode=... (all given as --opt=value)
    *) break ;;
  esac
done
[ "$#" -gt 0 ] || exit 0
exec "$@"
