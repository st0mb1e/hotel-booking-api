# Справочник API

Внешний API доступен через Gateway на `http://localhost:8080`.

Swagger доступен у [Hotel Service](http://localhost:8081/swagger-ui.html) и [Booking Service](http://localhost:8082/swagger-ui.html). Спецификация находится по `/v3/api-docs` у каждого бизнес-сервиса.

Пример запросов приведён в [README](../README.md#пример-работы-с-api).

## Авторизация и пользователи

| Метод и путь | Доступ | Описание |
|---|---|---|
| `POST /api/auth/register` | Публичный | Регистрация USER и выдача JWT |
| `POST /api/auth/login` | Публичный | Вход |
| `GET /api/users`, `GET /api/users/{id}` | ADMIN | Пользователи |
| `POST /api/users` | ADMIN | Создание пользователя с ролью |
| `PATCH /api/users/{id}` | ADMIN | Изменение данных, роли или активности |
| `DELETE /api/users/{id}` | ADMIN | Отключение пользователя с сохранением истории |

## Бронирования

| Метод и путь | Доступ | Описание |
|---|---|---|
| `POST /api/bookings` | USER, ADMIN | Создание с `Idempotency-Key` |
| `GET /api/bookings` | USER, ADMIN | Только свои брони |
| `GET /api/bookings/{id}` | Владелец | Получение брони |
| `DELETE /api/bookings/{id}` | Владелец | Идемпотентная отмена |

### Параметры бронирования

Для бронирования нужно выполнить `POST /api/bookings` с заголовком `Idempotency-Key: trip-2027-01` и телом:

```json
{"roomId":1,"autoSelect":false,"startDate":"2027-01-10","endDate":"2027-01-13"}
```

Для автоматического подбора нужно указать `"autoSelect":true`. В этом режиме поле `hotelId` ограничивает поиск одним отелем, `roomId` игнорируется.

### Ограничения по датам

- Если даты примера уже прошли, нужно указать актуальные.
- Заезд не может быть в прошлом, выезд должен быть позже заезда.
- Максимальный срок бронирования составляет год.
- Период учитывается как `[startDate, endDate)`. Выезд и следующий заезд в один день не конфликтуют.

## Отели и статистика

| Метод и путь | Доступ | Описание |
|---|---|---|
| `GET /api/hotels?q=...` | USER, ADMIN | Поиск по названию/адресу |
| `GET /api/hotels/{id}` | USER, ADMIN | Отель |
| `POST /api/hotels`, `PATCH /api/hotels/{id}`, `DELETE /api/hotels/{id}` | ADMIN | Управление отелями |
| `GET /api/hotels/statistics?startDate=...&endDate=...` | ADMIN | Число резервирований, занятые ночи и процент загрузки |

## Номера

| Метод и путь | Доступ | Описание |
|---|---|---|
| `GET /api/rooms?startDate=...&endDate=...&hotelId=...` | USER, ADMIN | Свободные номера, порядок по id |
| `GET /api/rooms/recommend` | USER, ADMIN | Те же параметры, сортировка по загруженности |
| `GET /api/rooms/{id}` | USER, ADMIN | Данные номера |
| `GET /api/rooms/all` | ADMIN | Все действующие номера, включая недоступные |
| `POST /api/rooms`, `PATCH /api/rooms/{id}`, `DELETE /api/rooms/{id}` | ADMIN | Управление номерами |

## Поля запросов

| Операция | Обязательные поля |
|---|---|
| Создание номера | `hotelId`, `number`, `available` |
| Изменение номера | `number`, `available` |
| Создание и изменение отеля | `name`, `address` |

Номер с текущими или будущими бронями нельзя удалить или вывести из эксплуатации. Удаление отелей, номеров и пользователей сохраняет их историю.

## Пагинация

В списках с пагинацией `page` начинается с нуля, `size` принимает значения от 1 до 100. `/api/rooms/all` и отчёт статистики возвращают все записи.

## Совместимые пути

Для совместимости сохранены пути `/user/register`, `/user/auth`, `/user`, `/user/{id}`, `/booking`, `/booking/{id}` и `/bookings`.

- В `PATCH /user` необходимо указать `id` в теле.
- В `DELETE /user?id=...` необходимо указать `id` в строке запроса.

## Ошибки и трассировка

Ответы с ошибками содержат поля `timestamp`, `status`, `code`, `message`, `path`, `requestId` и `details`.

- При конфликте дат возвращается `409`.
- При недоступности сервиса возвращается `503`.
- Для поиска запроса в JSON-логах используется `X-Request-Id`.

Если освободить номер не удалось, Booking Service повторяет отмену в фоне. До её завершения в ответе будет `compensationPending: true`.

## Внутренний API

Для этих запросов необходим служебный JWT. Через Gateway они закрыты даже для ADMIN.

| Путь POST | Тело | Назначение |
|---|---|---|
| `/api/rooms/allocate` | `bookingId`, `startDate`, `endDate`, необязательный `hotelId` | Атомарный автоподбор и резервирование |
| `/api/rooms/{id}/confirm-availability` | `bookingId`, `startDate`, `endDate` | Резервирование выбранного номера |
| `/api/rooms/release` | `bookingId`, `reason` | Освобождение по идентификатору брони |
| `/api/rooms/{id}/release` | `bookingId`, `reason` | Совместимый путь с проверкой номера |

`reason` принимает `COMPENSATION` или `USER_CANCELLATION`. Ответ содержит `bookingId`, `roomId` и `status`.

Если отмена зарегистрирована раньше резервирования, `roomId` может быть `null`. Hotel сохраняет такую отмену и отклоняет последующие попытки создать резерв с тем же `bookingId`.
