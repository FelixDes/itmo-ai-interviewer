# AI-интервьюер

Асинхронное техническое видеоинтервью с ИИ-оценкой. Учебный проект, кейс Napoleon IT.

Документация:

| Файл | Что внутри |
|---|---|
| `docs/decisions.md` | Зафиксированные решения Р-1…Р-22, правила ИИ-оценки, что вне скоупа |
| `docs/architecture.md` | Топология, модули, стейт-машины, схема БД, порядок сборки |
| `docs/user-flow.md` | Экраны и сценарии рекрутера, кандидата, нанимающего менеджера |
| `docs/api.md` | Спецификация API 1.0 — контракт для фронтенда |
| `frontend/` | React 19 + TypeScript + Vite, девять экранов из `user-flow.md` |

Исходные материалы заказчика (ТЗ, транскрипт дискавери-созвона, примеры вакансий
и резюме кандидатов) в репозиторий не попадают: там персональные данные.
Они лежат локально в `docs/` и закрыты через `.gitignore`.

## Запуск

Инфраструктура в Docker, бэкенд — на хосте.

```bash
docker compose up -d      # postgres:5433, minio:9000/9001, asr:8100, tts:8101
./gradlew bootRun         # backend:8080, Flyway накатывает схему сам
```

Распознавание речи по умолчанию на процессоре. Если есть видеокарта NVIDIA
и `nvidia-container-toolkit`, GPU даёт примерно двадцатикратный выигрыш:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build asr
```

Первая сборка `asr` и `tts` долгая: в образы кладутся модели, чтобы на демо
не зависеть от интернета. Swagger UI — http://localhost:8080/swagger-ui.html

Фронтенд:

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Vite проксирует `/api` на бэкенд, поэтому origin один и куки ведут себя как
в проде. Загрузка ответов идёт мимо прокси — presigned URL ведёт прямо в MinIO.

Проверка:

```bash
curl -s localhost:8080/actuator/health
curl -X POST localhost:8080/api/demo/seed   # демо-вакансия с эталонными вопросами
```

API отвечает по всей спецификации `docs/api.md`. Данные в PostgreSQL,
распознавание речи — faster-whisper, озвучка — Silero, ИИ-часть — DeepSeek.
Каждый внешний сервис имеет запасной путь: без него система работает на
правилах и заглушках, интервью не встаёт.

### Ключ к модели

Ключ в репозитории не хранится. Положите его в `secrets.yaml` в корне проекта
(файл в `.gitignore`):

```yaml
app:
  llm:
    api-key: sk-...
```

Либо задайте переменной `LLM_API_KEY`. Без ключа система работает на правилах:
вопросы берутся из эталонного набора, оценка считается по признакам ответа.
Проверить связь: `curl -s localhost:8080/actuator/health` — компонент `llm`.

Провайдер меняется конфигом: `LLM_BASE_URL`, `LLM_MODEL`, `LLM_STRUCTURED_OUTPUT`.
Учётки рекрутеров: `recruiter` / `recruiter` и `anna` / `anna`.
Подробности для фронтенда — `docs/api.md` §0.1.

Остановить инфраструктуру: `docker compose down` (данные остаются в volumes),
`docker compose down -v` — вместе с данными.

## Доступы локально

| Что | Адрес | Логин / пароль |
|---|---|---|
| PostgreSQL | `localhost:5433/aiinterviewer` | `aiinterviewer` / `aiinterviewer` |
| MinIO S3 | `http://localhost:9000` | `aiinterviewer` / `aiinterviewer` |
| MinIO консоль | `http://localhost:9001` | то же |
| Бэкенд | `http://localhost:8080` | — |

Порт postgres 5433, а не 5432: 5432 на машине разработчика занят другим проектом.

## Требования к окружению

- Docker с compose
- JDK для запуска Gradle; сам проект собирается тулчейном 25, Gradle скачает его
  сам через foojay-resolver (первая сборка — несколько минут)
- Свободное место: MinIO отказывается принимать запись, если на диске мало
  свободного места, и пишет «Storage backend has reached its minimum free drive threshold»

Внешние сервисы (пока не подняты, конфигурируются в `application.yaml`):
LM Studio на `localhost:1234`, ASR на `localhost:8100`, TTS на `localhost:8101`.
