#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
for module in api-gateway booking-service hotel-service discovery-server; do
    pidfile="$ROOT/.local/pids/$module.pid"
    [ -f "$pidfile" ] || continue
    pid=$(cat "$pidfile")
    args=$(ps -p "$pid" -o args= 2>/dev/null || true)
    case "$args" in
        *"$ROOT/$module/target/$module-1.0.0.jar"*)
            kill "$pid" 2>/dev/null || true
            attempt=0
            while kill -0 "$pid" 2>/dev/null; do
                state=$(ps -p "$pid" -o stat= 2>/dev/null || true)
                case "$state" in *Z*|"") break ;; esac
                attempt=$((attempt + 1))
                if [ "$attempt" -ge 50 ]; then
                    echo "$module ещё завершает работу (PID $pid). Повторите остановку позже." >&2
                    exit 1
                fi
                sleep 0.2
            done
            echo "$module остановлен (PID $pid)"
            ;;
        "") ;;
        *) echo "PID $pid относится к другому процессу; пропущен" >&2 ;;
    esac
    rm -f "$pidfile"
done
