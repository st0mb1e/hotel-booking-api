#!/usr/bin/env python3
"""Проверка запущенной системы через Gateway; создаёт и отменяет тестовые брони."""

import concurrent.futures
import datetime as dt
import os
import time
import urllib.parse
import uuid

from api_client import ApiClient, expect

BASE = os.environ.get("API_URL", "http://127.0.0.1:8080").rstrip("/")

request = ApiClient(BASE).request


def main():
    credentials = {"username": os.environ.get("ADMIN_USERNAME", "admin"), "password": os.environ.get("ADMIN_PASSWORD", "Admin12345!")}
    for attempt in range(30):
        status, auth = request("POST", "/api/auth/login", body=credentials)
        if status == 200:
            break
        if status != 503:
            expect(200, status, "Вход администратора")
        time.sleep(1)
    expect(200, status, "Регистрация сервисов в Eureka")
    admin = auth["accessToken"]
    status, auth = request("POST", "/api/auth/register", body={"username": "smoke_" + uuid.uuid4().hex[:12], "password": "Smoke12345!"})
    expect(201, status, "Регистрация")
    token = auth["accessToken"]
    start = dt.date.today() + dt.timedelta(days=10)
    end = start + dt.timedelta(days=3)
    query = urllib.parse.urlencode({"startDate": start.isoformat(), "endDate": end.isoformat(), "size": 100})
    status, available = request("GET", "/api/rooms/recommend?" + query, token)
    expect(200, status, "Свободные номера")
    if not available["content"]:
        raise AssertionError("Для smoke-проверки нужен свободный номер")
    room = available["content"][0]["id"]
    payload = {"roomId": room, "autoSelect": False, "startDate": start.isoformat(), "endDate": end.isoformat()}
    key = "smoke-" + uuid.uuid4().hex
    status, booking = request("POST", "/api/bookings", token, payload, key)
    expect(201, status, "Бронирование")
    assert booking["status"] == "CONFIRMED"
    status, duplicate = request("POST", "/api/bookings", token, payload, key)
    expect(200, status, "Повтор запроса")
    assert duplicate["id"] == booking["id"]
    status, _ = request("DELETE", "/api/bookings/" + booking["id"], token)
    expect(200, status, "Отмена")

    def book_parallel(index):
        return request("POST", "/api/bookings", token, payload, "race-" + uuid.uuid4().hex)

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(book_parallel, range(8)))
    winners = [body for code, body in results if code == 201]
    assert len(winners) == 1, f"Ожидалось одно подтверждение, получено {len(winners)}"
    assert all(code in (201, 409) for code, _ in results), "Неожиданный статус при параллельном бронировании"
    status, _ = request("DELETE", "/api/bookings/" + winners[0]["id"], token)
    expect(200, status, "Отмена победившего запроса")
    auto = {"autoSelect": True, "startDate": start.isoformat(), "endDate": end.isoformat()}
    status, selected = request("POST", "/api/bookings", token, auto, "auto-" + uuid.uuid4().hex)
    expect(201, status, "Автоматический подбор")
    status, _ = request("DELETE", "/api/bookings/" + selected["id"], token)
    expect(200, status, "Отмена автоподбора")
    status, _ = request("GET", "/api/hotels/statistics?" + query, admin)
    expect(200, status, "Статистика администратора")
    status, _ = request("POST", f"/api/rooms/{room}/release", admin, {"bookingId": str(uuid.uuid4()), "reason": "COMPENSATION"})
    expect(403, status, "Закрытый внутренний маршрут")
    status, history = request("GET", "/api/bookings?size=100", token)
    expect(200, status, "История")
    assert history["totalElements"] == 10
    print("Smoke OK: JWT, Gateway/Eureka, бронирование, идемпотентность, 8 параллельных запросов, отмена, автоподбор, статистика и внутренние маршруты")


if __name__ == "__main__":
    main()
