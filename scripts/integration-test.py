#!/usr/bin/env python3
"""Проверка саги с настоящими Booking/Hotel и потерей ответов между ними."""

import datetime as dt
import http.server
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from api_client import ApiClient, expect

ROOT = Path(__file__).resolve().parents[1]
HOTEL_URL = os.environ.get("HOTEL_API_URL", "http://127.0.0.1:8081").rstrip("/")


class FaultProxy(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        releasing = self.path == "/api/rooms/release"
        if releasing and self.server.mode == "deferred":
            self.reply(503, b'{"code":"TEMPORARY_FAILURE"}')
            return
        headers = {name: self.headers[name] for name in
                   ("Authorization", "Content-Type", "X-Request-Id") if name in self.headers}
        request = urllib.request.Request(HOTEL_URL + self.path, data=body, headers=headers, method="POST")
        try:
            response = urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, result = response.status, response.read()
        if not releasing and self.server.mode != "healthy" and status == 200:
            status, result = 503, b'{"code":"LOST_REPLY"}'
        self.reply(status, result)

    def reply(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def wait_until(check, timeout, label):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.1)
    raise AssertionError("Истекло время ожидания: " + label)


def wait_for_start(api, process, log_path):
    def ready():
        if process.poll() is not None:
            raise RuntimeError(log_path.read_text()[-6000:])
        try:
            if api.request("GET", "/actuator/health")[0] != 200:
                return False
            status, _ = api.request("POST", "/api/auth/login", body={
                "username": "admin", "password": "Admin12345!"})
            return status == 200
        except urllib.error.URLError:
            return False
    wait_until(ready, 60, "запуск Booking Service")


def check_saga(api, hotel, proxy):
    status, auth = api.request("POST", "/api/auth/login", body={
        "username": "admin", "password": "Admin12345!"})
    expect(200, status, "Вход администратора")
    admin = auth["accessToken"]
    status, auth = api.request("POST", "/api/auth/register", body={
        "username": "saga_" + uuid.uuid4().hex[:12], "password": "Saga12345!"})
    expect(201, status, "Регистрация тестового пользователя")
    token = auth["accessToken"]
    status, created = hotel.request("POST", "/api/hotels", admin,
                                   {"name": "Проверка компенсации", "address": uuid.uuid4().hex})
    expect(201, status, "Создание тестового отеля")
    hotel_id = created["id"]
    booking_ids = []
    day = dt.date.today() + dt.timedelta(days=250)
    payload = {"autoSelect": True, "hotelId": hotel_id, "startDate": day.isoformat(),
               "endDate": (day + dt.timedelta(days=2)).isoformat()}
    query = urllib.parse.urlencode({"startDate": payload["startDate"], "endDate": payload["endDate"]})

    def occupied():
        status, report = hotel.request("GET", "/api/hotels/statistics?" + query, admin)
        expect(200, status, "Статистика резерва")
        return sum(room["occupiedNights"] for room in report["rooms"] if room["hotelId"] == hotel_id)

    try:
        status, _ = hotel.request("POST", "/api/rooms", admin,
                                  {"hotelId": hotel_id, "number": "101", "available": True})
        expect(201, status, "Создание тестового номера")
        for mode in ("lost-reply", "deferred"):
            proxy.mode = mode
            status, failure = api.request("POST", "/api/bookings", token, payload,
                                          "saga-" + uuid.uuid4().hex)
            expect(503, status, "Потерянный ответ резервирования")
            booking_id = failure["details"]["bookingId"]
            booking_ids.append(booking_id)
            status, booking = api.request("GET", "/api/bookings/" + booking_id, token)
            expect(200, status, "Отменённая бронь")
            assert booking["status"] == "CANCELLED" and booking["roomId"] is None
            if mode == "deferred":
                assert booking["compensationPending"] is True
                assert occupied() == 2, "Резерв должен существовать до успешной компенсации"
            proxy.mode = "healthy"
            wait_until(lambda: not api.request("GET", "/api/bookings/" + booking_id, token)[1]
                       ["compensationPending"], 15, "повтор компенсации")
            assert occupied() == 0
            status, rooms = hotel.request("GET", "/api/rooms/recommend?" + query + "&hotelId=" + str(hotel_id), token)
            expect(200, status, "Номер снова доступен")
            assert len(rooms["content"]) == 1 and rooms["content"][0]["timesBooked"] == 0
            print("Saga OK: " + mode + ", резерв освобождён по bookingId, счётчик восстановлен")
    finally:
        proxy.mode = "healthy"
        for booking_id in booking_ids:
            api.request("DELETE", "/api/bookings/" + booking_id, token)
        hotel.request("DELETE", "/api/hotels/" + str(hotel_id), admin)


def run():
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), FaultProxy) as proxy:
        proxy.mode = "healthy"
        thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        thread.start()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        env = dict(os.environ, JWT_PUBLIC_KEY="file:" + str(ROOT / ".local/keys/public.pem"),
                   JWT_PRIVATE_KEY="file:" + str(ROOT / ".local/keys/private.pem"))
        command = ["java", "-Xms64m", "-Xmx192m", "-jar",
                   str(ROOT / "booking-service/target/booking-service-1.0.0.jar"),
                   "--server.port=" + str(port), "--eureka.client.enabled=false",
                   "--spring.cloud.discovery.enabled=false", "--booking.recovery-delay=500",
                   "--hotel.base-url=http://127.0.0.1:" + str(proxy.server_port)]
        try:
            with tempfile.TemporaryDirectory(prefix="booking-saga-") as temp:
                log_path = Path(temp) / "booking.log"
                with log_path.open("w") as output:
                    process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
                    try:
                        api = ApiClient("http://127.0.0.1:" + str(port))
                        wait_for_start(api, process, log_path)
                        check_saga(api, ApiClient(HOTEL_URL), proxy)
                    except Exception:
                        output.flush()
                        saved_log = ROOT / ".local/logs/saga-integration.log"
                        saved_log.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(log_path, saved_log)
                        print("Лог дополнительного Booking Service:", saved_log)
                        raise
                    finally:
                        process.terminate()
                        try:
                            process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
        finally:
            proxy.shutdown()
            thread.join(timeout=5)


if __name__ == "__main__":
    run()
