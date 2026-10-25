#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
if [ "${1:-}" != "--skip-build" ]; then
    ./mvnw -B -q -DskipTests package
fi
./scripts/generate-keys.sh
mkdir -p .local/logs .local/pids
DISCOVERY_PORT=${DISCOVERY_PORT:-8761}
HOTEL_PORT=${HOTEL_PORT:-8081}
BOOKING_PORT=${BOOKING_PORT:-8082}
GATEWAY_PORT=${GATEWAY_PORT:-8080}
export EUREKA_URL=${EUREKA_URL:-http://localhost:$DISCOVERY_PORT/eureka/}
export JWT_PUBLIC_KEY="file:$ROOT/.local/keys/public.pem"
export JWT_PRIVATE_KEY="file:$ROOT/.local/keys/private.pem"
start_service() {
    module=$1
    port=$2
    jar="$ROOT/$module/target/$module-1.0.0.jar"
    pidfile="$ROOT/.local/pids/$module.pid"
    running=false
    if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        args=$(ps -p "$(cat "$pidfile")" -o args= 2>/dev/null || true)
        case "$args" in *"$jar"*) running=true ;; esac
    fi
    if [ "$running" = true ]; then
        echo "$module уже запущен (PID $(cat "$pidfile"))"
    else
        if [ ! -f "$jar" ]; then echo "Не найден $jar. Выполните ./mvnw package" >&2; exit 1; fi
        if curl --silent --max-time 1 "http://127.0.0.1:$port/actuator/health" >/dev/null; then
            echo "Порт $port уже занят. Остановите процесс или запустите приложения вручную на других портах." >&2
            exit 1
        fi
        nohup java -Xms64m -Xmx256m -jar "$jar" --server.port="$port" >"$ROOT/.local/logs/$module.log" 2>&1 &
        echo "$!" > "$pidfile"
    fi
    attempt=0
    until curl --fail --silent --max-time 2 "http://127.0.0.1:$port/actuator/health" >/dev/null; do
        attempt=$((attempt + 1))
        if [ "$attempt" -ge 60 ] || ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then
            echo "$module не запустился. Лог: $ROOT/.local/logs/$module.log" >&2
            tail -n 30 "$ROOT/.local/logs/$module.log" >&2
            exit 1
        fi
        sleep 1
    done
    echo "$module: http://localhost:$port"
}
start_service discovery-server "$DISCOVERY_PORT"
start_service hotel-service "$HOTEL_PORT"
start_service booking-service "$BOOKING_PORT"
start_service api-gateway "$GATEWAY_PORT"
echo "Система запущена. Проверка: python3 scripts/smoke-test.py"
