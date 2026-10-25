# Настройка и запуск

Обычный запуск описан в [README](../README.md#запуск). Команды нужно выполнять из корня репозитория.

Для проверки состояния приложения используется `/actuator/health`.

## Другие порты

Если стандартные порты заняты, нужно указать другие значения:

```sh
DISCOVERY_PORT=18761 HOTEL_PORT=18081 BOOKING_PORT=18082 GATEWAY_PORT=18080 ./scripts/run-local.sh --skip-build
```

Для HTTP-проверок на этих портах нужно задать `API_URL` для `smoke-test.py` и `load-test.py`, а `HOTEL_API_URL` для `integration-test.py`. Команды приведены в [инструкции по проверке](verification.md).

## Переменные окружения

| Переменная | Значение по умолчанию |
|---|---|
| `EUREKA_URL` | `http://localhost:8761/eureka/` |
| `JWT_PUBLIC_KEY` | `file:./.local/keys/public.pem` |
| `JWT_PRIVATE_KEY` | `file:./.local/keys/private.pem`, нужен только Booking |
| `DEMO_ENABLED` | `true`. Значение `false` отключает демоданные |
| `HOTEL_URL` | Не задан, адрес Hotel берётся из Eureka |
| `PORT` | Порт соответствующего приложения |

## Демоданные

При `DEMO_ENABLED=true` создаются два отеля, четыре доступных номера и один номер вне эксплуатации. Пользователи `admin` и `alice` указаны в [примере API](../README.md#пример-работы-с-api). Для отключения демоданных нужно задать `DEMO_ENABLED=false`.

## Ручной запуск

На Linux и macOS для создания ключей нужно выполнить `./scripts/generate-keys.sh`. Ключи сохраняются в `.local/keys`.

Каждую команду нужно выполнить в отдельном терминале. Сначала запускается Eureka, затем Hotel и Booking, после них Gateway:

```sh
java -jar discovery-server/target/discovery-server-1.0.0.jar
java -jar hotel-service/target/hotel-service-1.0.0.jar
java -jar booking-service/target/booking-service-1.0.0.jar
java -jar api-gateway/target/api-gateway-1.0.0.jar
```

## Запуск на Windows

Для сборки нужно выполнить `.\mvnw.cmd verify`. Скрипты `.sh` рассчитаны на Linux и macOS.

Необходимо создать каталог `.local/keys`, затем выполнить команды OpenSSL:

```sh
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/keys/private.pem
openssl pkey -in .local/keys/private.pem -pubout -out .local/keys/public.pem
```

После этого нужно запустить JAR в порядке, указанном в разделе [«Ручной запуск»](#ручной-запуск).

## Служебные файлы

- `.local/keys` содержит ключи JWT.
- `.local/logs` содержит логи приложений.
- `.local/pids` содержит файлы с PID для `stop-local.sh`.

Каталог `.local` исключён из Git.
