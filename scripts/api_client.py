"""HTTP-клиент для проверок локально запущенных сервисов."""

import json
import urllib.error
import urllib.request
import uuid


class ApiClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def request(self, method, path, token=None, body=None, key=None):
        headers = {"Accept": "application/json", "X-Request-Id": "check-" + uuid.uuid4().hex}
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
        if key:
            headers["Idempotency-Key"] = key
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            return response.status, json.loads(data) if data else {}


def expect(expected, actual, label):
    if actual != expected:
        raise AssertionError(f"{label}: ожидался HTTP {expected}, получен {actual}")
