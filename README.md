# OpticalPositionSensor

Android-приложение, превращающее телефон (или ТВ-приставку с USB-камерой) в **оптический датчик перемещения** — аналог сенсора **ADNS-3080**. Устройство определяет смещение и поворот в плоскости «как оптическая мышь», публикует данные по сети и предоставляет веб-интерфейс для настройки и мониторинга.

Поддерживаются старые устройства (проверено на **ZTE T221, Android 4.4.2**) и более новые (**AndroidTV X96mini, Android 7** с USB-камерой).

---

## Возможности

### Оптический поток
- Оценка движения по видео-потоку камеры: **block matching (SAD)** + **МНК-аппроксимация модели «сдвиг + поворот»**.
- Работает с камерой через классический `android.hardware.Camera` API (совместимо с API 19+).
- Автоматический выбор минимального разрешения превью для максимальной скорости (обычно 176×144).
- Кадр уменьшается до ~112 px по ширине → обработка 10–30 мс на кадр на слабых SoC.
- Поворот изображения и вектора движения — настраивается под монтаж устройства.

### JSON-измерение
На каждый значимый кадр формируется объект:
```json
{
  "seq": 128,
  "timestamp_ms": 1710000000123,
  "datetime": "2024-03-09T15:20:00.123+0300",
  "dt_ms": 33,
  "frame": { "width": 176, "height": 144, "downscale": 2 },
  "shift": {
    "dx_px": 2.345,
    "dy_px": -0.812,
    "distance_px": 2.482,
    "direction_deg": 340.91,
    "rotation_deg": 0.184,
    "mount_rotation_deg": 0,
    "invert_dx": false,
    "invert_dy": true
  },
  "quality": { "blocks_used": 41, "blocks_total": 99, "confidence": 0.414 },
  "flashlight": { "mode": "off", "on": false, "available": true, "lux": 42.0 },
  "proc_ms": 8.73,
  "publish_reason": "motion"
}
```

### Сетевые интерфейсы (серверный режим)
По умолчанию включены, настраиваются в конфиге:

| Протокол | Адрес | Что отдаёт |
|---|---|---|
| **HTTP REST** | `GET /api/sensor/last` | последнее измерение в JSON |
| | `GET /api/sensor/status` | состояние сервиса, IP, порт, фонарик |
| | `POST /api/sensor/start` / `stop` | управление трансляцией |
| | `POST /api/sensor/flashlight` | `{"mode":"off\|on\|auto","threshold":50}` |
| | `GET/POST /api/sensor/config` | настройки (Basic auth, admin/admin) |
| | `POST /api/sensor/restart-http` | смена HTTP-порта с перезапуском |
| | `POST /api/sensor/push` | приём данных от других таких же устройств |
| **SSE** | `GET /api/sensor/sse` | поток измерений (Server-Sent Events) |
| **WebSocket** | `ws://<ip>:8090/` | JSON-текст-фреймы |
| **Telnet/TCP** | `<ip>:8091` | JSON построчно + команды `GET`, `FLASHLIGHT on`, `STATUS` |
| **MJPEG** | `GET /api/sensor/stream.mjpg` | видео-поток с камеры |
| | `GET /api/sensor/snapshot.jpg` | одиночный JPEG |
| **Web UI** | `GET /` | веб-интерфейс (Live / Информация / Настройки) |

### Клиентский режим (push наружу)
Приложение может **само** отправлять измерения на удалённый сервер:
- `http` — POST JSON по указанному URL и пути;
- `ws` — WebSocket text-frame;
- `telnet` — TCP, JSON + `\r\n`.

Настраивается URL, путь, Bearer-токен, интервал отправки.

### Фонарик
Три режима:
- `off` — выключен;
- `on` — включён постоянно;
- `auto` — включается, когда освещённость ниже порога (данные с датчика света).

Фонарик управляется через режим `TORCH` камеры (та же камера, что использует optical flow), с обходными путями для капризных HAL (Spreadtrum/старые MTK):
- пересоздание `Camera.Parameters` при каждой смене;
- `cancelAutoFocus()` перед установкой режима;
- fallback `OFF → пауза → TORCH`, если прямая установка «проглатывается»;
- проверка `getParameters().getFlashMode()` после `setParameters()` — в статус отдаётся `appliedMode` и `lastError`.

### Веб-интерфейс (assets/www)
- **Информация** — крупный блок «Подключение к устройству» с `http://<IP>:<port>/`, кнопки «Копировать» для HTTP / SSE / WS / Telnet, состояние сервисов, список REST-эндпоинтов.
- **Live** — MJPEG-превью с камеры, кнопки «▶ Запустить» / «■ Остановить», значения dx / dy / distance / direction / rotation, управление фонариком, последний JSON.
- **Настройки** — все параметры (порты, оптический поток, публикация, фонарик, ориентация осей, превью) с HTTP Basic-аутентификацией. Пароль по умолчанию `admin/admin`, меняется на той же странице.

### Публикация «только при изменении»
По умолчанию JSON публикуется только тогда, когда положение **изменилось**:
- первый кадр после старта — всегда;
- движение больше порога (`|dx|` / `|dy|` / `|rotation|`) — публикуется;
- изменение состояния фонарика — публикуется;
- heartbeat раз в N секунд (по умолчанию 5 с) — публикуется;
- всё остальное — не публикуется, но `GET /api/sensor/last` всегда отдаёт свежее значение.

Порог смещения и поворота, а также heartbeat настраиваются.

### Работа после перезагрузки
`BootReceiver` автоматически запускает сервис по `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`. Сервер поднимается на порту из сохранённого `config.json` (по умолчанию `8080`).

Смена порта не требует перезагрузки приложения: `POST /api/sensor/restart-http` останавливает HTTP-сервер и поднимает его на новом порту, клиент автоматически перенаправляется.

---

## Структура проекта

```
app/src/main/java/ru/miacomsoft/myapplication/
├── MainActivity.java          — WebView (Live / Настройки) + плавающая кнопка
├── SensorService.java         — служба-хост: камера, серверы, клиент
├── BootReceiver.java          — автозапуск после перезагрузки
├── SensorConfig.java          — все настройки
├── ConfigStore.java           — чтение/запись config.json (assets + filesDir)
├── OpticalFlowTracker.java    — block matching + МНК (сдвиг + поворот)
├── FlashlightController.java  — off/on/auto, обходы HAL-багов
├── SensorBroadcaster.java     — pub/sub: последнее измерение + подписчики
├── SnapshotProvider.java      — кодирование NV21 → JPEG (для MJPEG)
├── HttpApiServer.java         — HTTP-сервер (com.sun.net.httpserver)
├── RestHandler.java           — /api/sensor/*
├── SseHandler.java            — /api/sensor/sse
├── SnapshotHandler.java       — /api/sensor/stream.mjpg, /snapshot.jpg
├── StaticHandler.java         — assets/www/*
├── WebSocketServer.java       — свой WS-сервер (ServerSocket)
├── TelnetServer.java          — свой Telnet-сервер (ServerSocket)
├── PushClient.java            — исходящий клиент (http/ws/telnet)
└── HttpUtil.java              — утилиты, CORS, Basic auth, определение IP

app/src/main/assets/
├── config.json                — значения по умолчанию
└── www/
    ├── index.html             — Информация (IP, адреса, статус)
    ├── sensor.html            — Live (MJPEG, JSON, управление)
    └── config.html            — Настройки
```

---

## Быстрый старт

### Сборка
```bash
./gradlew assembleDebug
```

Требования: **minSdk 19**, **compileSdk 32**, AGP 7.1.3, Gradle 7.x. Единственная зависимость — `androidx.appcompat`.

### Запуск
1. Установить APK.
2. Разрешить доступ к камере.
3. Сервис стартует автоматически, HTTP-сервер поднимается на `:8080`.
4. WebView открывает `http://127.0.0.1:8080/sensor.html`.

### Подключение с другого устройства
1. Открыть на телефоне вкладку **«Информация»** или уведомление сервиса — там будет крупный адрес `http://<IP>:8080/`.
2. На ПК/другом телефоне в той же сети открыть этот адрес.
3. Или подключиться напрямую:
   - SSE — `curl -N http://<IP>:8080/api/sensor/sse`
   - WS — `ws://<IP>:8090/`
   - Telnet — `telnet <IP> 8091`

---

## Конфигурация (`config.json`)

Файл копируется из `assets/config.json` в `<external>/files/config.json` при первом запуске. Дальнейшие правки (через веб-интерфейс или вручную) перезаписывают его.

Основные ключи в секции `"sensor"`:

| Ключ | По умолчанию | Назначение |
|---|---|---|
| `enabled` | `true` | Общий выключатель |
| `autoStart` | `true` | Автозапуск камеры при старте сервиса |
| `serverEnabled` | `true` | Поднимать HTTP/WS/Telnet |
| `restEnabled` / `sseEnabled` | `true` | Включить REST / SSE |
| `wsEnabled` / `wsPort` | `true` / `8090` | WebSocket |
| `telnetEnabled` / `telnetPort` | `false` / `8091` | Telnet |
| `clientEnabled` | `false` | Push-клиент наружу |
| `clientProtocol` | `"http"` | `http` / `ws` / `telnet` |
| `clientServerUrl` | `""` | URL удалённого сервера |
| `clientPath` | `"/api/sensor/push"` | Путь (для HTTP) |
| `clientIntervalMs` | `100` | Интервал отправки |
| `targetWidth` | `112` | К какому размеру приводить кадр |
| `searchRadius` | `4` | Радиус поиска block matching |
| `blockSize` | `8` | Размер блока |
| `minContrast` | `3` | Порог контраста блока |
| `maxSadPx` | `18` | Порог SAD |
| `flashlightMode` | `"off"` | `off` / `on` / `auto` |
| `lightThreshold` | `50` | Порог освещённости для auto, lx |
| `cameraPreviewEnabled` | `true` | MJPEG-превью |
| `cameraPreviewFps` | `5` | FPS превью |
| `cameraPreviewQuality` | `50` | Качество JPEG |
| `cameraPreviewRotation` | `90` | Поворот картинки (0/90/180/270) |
| `cameraMountRotationDeg` | `0` | Поворот вектора движения |
| `invertDx` / `invertDy` | `false` / `true` | Инверсия осей (для «телефон плашмя») |
| `publishOnlyOnChange` | `true` | Публиковать только при изменении |
| `publishMotionThresholdPx` | `0.4` | Порог смещения |
| `publishRotationThresholdDeg` | `0.2` | Порог поворота |
| `publishHeartbeatMs` | `5000` | Heartbeat (0 = выключить) |
| `configUser` / `configPass` | `admin` / `admin` | Доступ к настройкам |

---

## Совместимость и ограничения

- **Камера:** `android.hardware.Camera` (deprecated, но работает и на 4.4, и на 7+). Camera2 не используется — его нет на API 19.
- **Фонарик:** через `Camera.Parameters.FLASH_MODE_TORCH`. На устройствах без flash → `available: false`.
- **USB-камера на X96mini:** работает, если прошивка пробрасывает её в HAL. Если нет — нужна библиотека UVCCamera (алгоритм `OpticalFlowTracker` от этого не зависит).
- **WebView:** протестировано на старом Chromium (API 19). Для MJPEG есть fallback на JPEG-polling через `/snapshot.jpg`.
- **HTTP-сервер:** используется скопированный `com.sun.net.httpserver` (без `java.util.Base64`, без `ConcurrentHashMap.newKeySet` — заменено на API-19-совместимые эквиваленты).

---

## Производительность (ZTE T221, 176×144)

| Операция | Время |
|---|---|
| Optical flow (1 кадр) | 8–12 мс |
| JPEG-кодирование (snapshot) | 5–8 мс |
| Поворот + JPEG (MJPEG) | 12–18 мс |
| Публикация в SSE (1 подписчик) | < 1 мс |

На практике: **~25–35 FPS** обработки optical flow, **~5 FPS** MJPEG-превью, задержка SSE-потока ~50 мс.

---

## Лицензия

Проект распространяется свободно. Используйте на свой страх и риск.
