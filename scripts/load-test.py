#!/usr/bin/env python3
"""Параллельный автоподбор через Gateway на отдельном наборе тестовых данных."""

import concurrent.futures
import datetime as dt
import os
import time
import urllib.parse
import uuid

from api_client import ApiClient, expect


def run():
    api = ApiClient(os.environ.get("API_URL", "http://127.0.0.1:8080"))
    status, auth = api.request("POST", "/api/auth/login", body={
        "username": os.environ.get("ADMIN_USERNAME", "admin"),
        "password": os.environ.get("ADMIN_PASSWORD", "Admin12345!")})
    expect(200, status, "Вход администратора")
    admin = auth["accessToken"]
    status, auth = api.request("POST", "/api/auth/register", body={
        "username": "load_" + uuid.uuid4().hex[:12], "password": "Load12345!"})
    expect(201, status, "Регистрация тестового пользователя")
    token = auth["accessToken"]
    status, hotel = api.request("POST", "/api/hotels", admin,
                              {"name": "Нагрузочный тест", "address": uuid.uuid4().hex})
    expect(201, status, "Создание тестового отеля")
    hotel_id = hotel["id"]
    confirmed = []
    start = dt.date.today() + dt.timedelta(days=100)
    try:
        for i in range(4):
            status, _ = api.request("POST", "/api/rooms", admin,
                                   {"hotelId": hotel_id, "number": str(100 + i), "available": True})
            expect(201, status, "Создание тестового номера")

        def book(index, same_dates=False):
            day = start + dt.timedelta(days=90 if same_dates else index * 2)
            return api.request("POST", "/api/bookings", token, {
                "autoSelect": True, "hotelId": hotel_id, "startDate": day.isoformat(),
                "endDate": (day + dt.timedelta(days=1)).isoformat()}, "load-" + uuid.uuid4().hex)

        before = time.monotonic()
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as executor:
            results = list(executor.map(book, range(32)))
        confirmed.extend(body["id"] for code, body in results if code == 201)
        assert all(code == 201 for code, _ in results), f"Статусы автоподбора: {[code for code, _ in results]}"
        query = urllib.parse.urlencode({"startDate": start.isoformat(),
                                       "endDate": (start + dt.timedelta(days=64)).isoformat()})
        status, report = api.request("GET", "/api/hotels/statistics?" + query, admin)
        expect(200, status, "Статистика нагрузки")
        counts = [room["timesBooked"] for room in report["rooms"] if room["hotelId"] == hotel_id]
        assert sorted(counts) == [8, 8, 8, 8], f"Неравномерное распределение: {counts}"
        print(f"Load OK: 32 параллельных запроса, распределение {counts}, {time.monotonic() - before:.2f} с")

        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
            results = list(executor.map(lambda i: book(i, True), range(16)))
        winners = [body for code, body in results if code == 201]
        confirmed.extend(body["id"] for body in winners)
        assert len(winners) == 4, f"На одни даты должны заняться ровно четыре номера: {len(winners)}"
        assert len({body["roomId"] for body in winners}) == 4
        assert all(code in (201, 409) for code, _ in results)
        print("Capacity OK: 16 параллельных запросов на одни даты, 4 подтверждения и 12 конфликтов")
    finally:
        for booking_id in confirmed:
            status, _ = api.request("DELETE", "/api/bookings/" + booking_id, token)
            expect(200, status, "Освобождение тестовой брони")
        status, _ = api.request("DELETE", "/api/hotels/" + str(hotel_id), admin)
        expect(204, status, "Удаление тестового отеля")


if __name__ == "__main__":
    run()
