# AI-интервьюер

Асинхронное техническое видеоинтервью с ИИ-оценкой. Учебный проект, кейс Napoleon IT.

Документация:

| Файл | Что внутри |
|---|---|
| `docs/decisions.md` | Зафиксированные решения Р-1…Р-22, правила ИИ-оценки, что вне скоупа |
| `docs/architecture.md` | Топология, модули, стейт-машины, схема БД, порядок сборки |
| `docs/user-flow.md` | Экраны и сценарии рекрутера, кандидата, нанимающего менеджера |
| `docs/api.md` | Спецификация API 1.0 — контракт для фронтенда |

Исходные материалы заказчика (ТЗ, транскрипт дискавери-созвона, примеры вакансий
и резюме кандидатов) в репозиторий не попадают: там персональные данные.
Они лежат локально в `docs/` и закрыты через `.gitignore`.

## Запуск

Инфраструктура в Docker, бэкенд — на хосте.

```bash
docker compose up -d      # postgres:5433, minio:9000, консоль minio:9001
./gradlew bootRun         # backend:8080, Flyway накатывает схему сам
```

Проверка:

```bash
curl -s localhost:8080/actuator/health
```

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
