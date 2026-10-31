# Настройка запуска

[Обычный запуск](../README.md#запуск) описан в README. Команды нужно выполнять из корня проекта. Скрипты `.sh` рассчитаны на Linux и macOS.

## Переменные окружения

| Переменная | Значение по умолчанию |
|---|---|
| `EUREKA_URL` | `http://localhost:8761/eureka/` |
| `JWT_PUBLIC_KEY` | `file:./.local/keys/public.pem` |
| `JWT_PRIVATE_KEY` | `file:./.local/keys/private.pem`, нужен только Booking |
| `DEMO_ENABLED` | `true`. Значение `false` отключает демоданные |
| `HOTEL_URL` | Не задан, адрес Hotel берётся из Eureka |
| `PORT` | Порт соответствующего приложения |

HTTP-проверки используют `API_URL` с адресом Gateway и `HOTEL_API_URL` с адресом Hotel Service. По умолчанию это `http://127.0.0.1:8080` и `http://127.0.0.1:8081`.

## Другие порты

```sh
DISCOVERY_PORT=18761 HOTEL_PORT=18081 BOOKING_PORT=18082 GATEWAY_PORT=18080 ./scripts/run-local.sh --skip-build
```

Для проверки на этих портах нужно задать `API_URL=http://127.0.0.1:18080` и `HOTEL_API_URL=http://127.0.0.1:18081`.

## Ручной запуск

Сначала нужно создать ключи командой `./scripts/generate-keys.sh`. Затем выполнить каждую команду в отдельном терминале в указанном порядке:

```sh
java -jar discovery-server/target/discovery-server-1.0.0.jar
java -jar hotel-service/target/hotel-service-1.0.0.jar
java -jar booking-service/target/booking-service-1.0.0.jar
java -jar api-gateway/target/api-gateway-1.0.0.jar
```

Логи находятся в `.local/logs`, PID в `.local/pids`, ключи в `.local/keys`. Каталог `.local` исключён из Git. Состояние приложения доступно по `/actuator/health`.
