# Схема данных

Booking и Hotel используют отдельные H2 в памяти. Схему создаёт Flyway, Hibernate проверяет её через `ddl-auto: validate`.

| Сервис | Таблица | Данные |
|---|---|---|
| Booking | `users` | Логин, BCrypt-хеш пароля, роль и признак активности |
| Booking | `bookings` | UUID, ссылка на `users`, номер, даты, статус, `request_key`, `fingerprint`, состояние и срок повтора компенсации |
| Hotel | `hotels` | Название, адрес и признак удаления |
| Hotel | `rooms` | Ссылка на `hotels`, номер комнаты, `available`, `times_booked`, признак удаления |
| Hotel | `room_reservations` | Календарь по `booking_id`, ссылка на `rooms`, даты, статус и причина отмены |
| Hotel | `reservation_guard` | Строка блокировки с `id = 1` |

Уникальны `username`, `(user_id, request_key)` и `(hotel_id, number)`. Индексы используются для истории, очереди компенсаций, поиска зависших броней, календаря и рекомендаций.

У брони, номера и резерва есть `version`. Логическое удаление сохраняет историю. Для записи `RELEASED` без предшествующего резерва номер и даты могут быть пустыми.

Полные поля, связи и ограничения: [Booking V1](../booking-service/src/main/resources/db/migration/V1__booking_schema.sql), [Hotel V1](../hotel-service/src/main/resources/db/migration/V1__hotel_schema.sql), [Hotel V2](../hotel-service/src/main/resources/db/migration/V2__atomic_allocation.sql).
