# API

Адрес Gateway: `http://localhost:8080`. Для запросов нужен JWT, кроме регистрации и входа. [Пример запросов](../README.md#пример-работы-с-api).

## Методы

| Метод и путь | Доступ | Описание |
|---|---|---|
| `POST /api/auth/register` | Публичный | Регистрация USER и выдача JWT |
| `POST /api/auth/login` | Публичный | Вход |
| `GET /api/users`, `GET /api/users/{id}` | ADMIN | Пользователи |
| `POST /api/users` | ADMIN | Создание пользователя с ролью |
| `PATCH /api/users/{id}` | ADMIN | Изменение данных, роли или активности |
| `DELETE /api/users/{id}` | ADMIN | Отключение пользователя с сохранением истории |
| `POST /api/bookings` | USER, ADMIN | Создание с `Idempotency-Key` |
| `GET /api/bookings` | USER, ADMIN | Только свои брони |
| `GET /api/bookings/{id}` | Владелец | Получение брони |
| `DELETE /api/bookings/{id}` | Владелец | Идемпотентная отмена |
| `GET /api/hotels?q=...` | USER, ADMIN | Поиск по названию/адресу |
| `GET /api/hotels/{id}` | USER, ADMIN | Отель |
| `POST /api/hotels`, `PATCH /api/hotels/{id}`, `DELETE /api/hotels/{id}` | ADMIN | Управление отелями |
| `GET /api/hotels/statistics?startDate=...&endDate=...` | ADMIN | Число резервирований, занятые ночи и процент загрузки |
| `GET /api/rooms?startDate=...&endDate=...&hotelId=...` | USER, ADMIN | Свободные номера, порядок по id |
| `GET /api/rooms/recommend` | USER, ADMIN | Те же параметры, сортировка по загруженности |
| `GET /api/rooms/{id}` | USER, ADMIN | Данные номера |
| `GET /api/rooms/all` | ADMIN | Все действующие номера, включая недоступные |
| `POST /api/rooms`, `PATCH /api/rooms/{id}`, `DELETE /api/rooms/{id}` | ADMIN | Управление номерами |

## Параметры

Для брони нужны `startDate`, `endDate` и `roomId`. При `autoSelect: true` номер подбирается автоматически, `roomId` игнорируется. Необязательный `hotelId` ограничивает подбор одним отелем.

Заезд не может быть в прошлом, выезд должен быть позже заезда. Срок проживания не больше года. Период `[startDate, endDate)` позволяет заехать в день выезда предыдущего гостя.

Для создания брони нужен `Idempotency-Key`. Повтор с теми же параметрами не создаёт новую бронь, изменение параметров возвращает `409`.

- Отель: `name`, `address`.
- Номер: `number`, `available`. При создании также нужен `hotelId`.
- Пагинация: `page` от 0, `size` от 1 до 100. `/api/rooms/all` и статистика возвращаются без пагинации.

Также поддерживаются `/user/register`, `/user/auth`, `/user`, `/user/{id}`, `/booking`, `/booking/{id}` и `/bookings`. Для `PATCH /user` нужен `id` в теле, для `DELETE /user?id=...` в строке запроса.

## Внутренние методы

Доступны со служебным JWT. Через Gateway закрыты.

| Путь POST | Тело | Назначение |
|---|---|---|
| `/api/rooms/allocate` | `bookingId`, `startDate`, `endDate`, необязательный `hotelId` | Атомарный автоподбор и резервирование |
| `/api/rooms/{id}/confirm-availability` | `bookingId`, `startDate`, `endDate` | Резервирование выбранного номера |
| `/api/rooms/release` | `bookingId`, `reason` | Освобождение по идентификатору брони |
| `/api/rooms/{id}/release` | `bookingId`, `reason` | Совместимый путь с проверкой номера |

`reason` принимает `COMPENSATION` или `USER_CANCELLATION`. Ответ содержит `bookingId`, `roomId` и `status`. У отмены без резерва `roomId` равен `null`.

## Ошибки

Поля ответа: `timestamp`, `status`, `code`, `message`, `path`, `requestId`, `details`. Коды ошибок: `409` при конфликте дат, `503` при недоступности сервиса. Запрос можно найти в логах по `X-Request-Id`.

`compensationPending: true` означает, что освобождение номера ещё выполняется в фоне.
