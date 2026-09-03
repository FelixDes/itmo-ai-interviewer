# AI-интервьюер — архитектура

Статус: предложение v1, 2026-09-03. Решения из `decisions.md` считаются входными.
Спорные места помечены **[?]** — по ним нужен ответ до начала кода.

## 1. Топология

Всё поднимается одним `docker compose up`.

```
                    ┌─────────────┐
   браузер ───────► │  frontend   │  nginx + React SPA
                    └──────┬──────┘
                           │ REST + presigned URL
                    ┌──────▼──────────────────────────┐
                    │          backend                │  Spring Boot / Kotlin
                    │  оркестрация, домен, доступы    │
                    └─┬───────┬────────┬──────┬───────┘
                      │       │        │      │
        ┌─────────────▼─┐ ┌───▼────┐ ┌─▼────┐ │
        │   postgres    │ │ minio  │ │ tts  │ │  silero, RU
        └───────────────┘ └───▲────┘ └──────┘ │
                              │               │
                         ┌────┴───┐    ┌──────▼─────────┐
                         │  asr   │    │  LLM endpoint  │  OpenAI-совместимый
                         │ ffmpeg │    │  (LM Studio)   │  вне compose, на хосте
                         └────────┘    └────────────────┘
```

| Контейнер | Что внутри | Почему отдельно |
|---|---|---|
| `frontend` | nginx, статика React | — |
| `backend` | Spring Boot 4.1, Kotlin, JDK 25 | Весь домен и оркестрация |
| `postgres` | PostgreSQL 17 | — |
| `minio` | S3 API | Видео, аудио ответов, аудио вопросов |
| `asr` | Python + FastAPI + ffmpeg + NeMo | Тяжёлые ML-зависимости не тащим в JVM. ffmpeg живёт здесь же: нормализация в wav 16 kHz нужна только ASR |
| `tts` | Python + FastAPI + Silero | То же — Python-модель |
| LLM | LM Studio на хосте | Локально, конфигурируется тремя настройками (Р-5) |

Браузер ходит в MinIO напрямую по presigned URL — и на запись чанков, и на
чтение видео в карточке. Через backend медиа не гоняем.

**Решено (Р-17).** nginx проксирует `/s3/*` в MinIO, backend подписывает URL с
публичным базовым адресом `http://localhost/s3`. Через JVM медиа не идёт.

## 2. Модули бэкенда

Один Gradle-модуль, границы — пакетами. Домен не знает про Spring.

```
com.itmo.napoleonit.aiinterviewer
├─ domain/          сущности, value-объекты, стейт-машины. Без Spring
├─ vacancy/         вакансии, требования с весами и стоп-факторами
├─ questions/       QuestionGenerator, FollowUpGenerator + реализации
├─ interview/       сессия интервью, оркестрация прохождения
├─ media/           S3, presigned URL, ключи объектов
├─ transcription/   AsrEngine, TranscriptRefiner
├─ evaluation/      рубрика, оценка ответа, сборка карточки результата
├─ report/          карточка, фидбэк кандидату
├─ llm/             OpenAI-совместимый клиент, structured output
├─ tts/             TtsEngine
├─ access/          токены ссылок, роли, TTL, отзыв
└─ web/             REST-контроллеры, DTO
```

Правило зависимостей: `web` → сервисы → `domain`. Обратных стрелок нет.
`llm` не знает про интервью, `questions`/`evaluation` не знают про HTTP.

Все внешние ML-вызовы за интерфейсами (`AsrEngine`, `TtsEngine`, `LlmClient`,
`QuestionGenerator`, `FollowUpGenerator`) — требование Р-7, Р-8 и обещание
заказчику про сменяемость моделей (`[8:17]`).

## 3. Стейт-машины

Это ядро корректности. Оба статуса видны в UI — Рамка §7 требует показывать
состояние обработки.

### Интервью

```
CREATED ──consent──► READY ──start──► IN_PROGRESS ──все ответы──► ANALYZING
                                           │                          │
                                           │                          ├─► READY_REPORT
                                        EXPIRED                       └─► FAILED
```

- `CREATED` → `READY`: кандидат поставил чекбокс согласия (Р-3), прошёл проверку
  камеры и микрофона.
- `IN_PROGRESS`: есть текущий вопрос. Возврат по ссылке после потери сети
  возобновляет с него же.
- `ANALYZING`: последний ответ обработан, собирается карточка.
- `EXPIRED`: истёк TTL ссылки.

### Ответ

Маппится на статусы Рамки §7 один в один:

```
RECORDING ──► UPLOADED ──► TRANSCRIBING ──► TRANSCRIBED ──► EVALUATING ──► EVALUATED
                                │                                │
                                └──────────► FAILED ◄────────────┘
                                                │
                                       UNRATEABLE (низкое качество записи)
```

`UNRATEABLE` — отдельно от `FAILED`. Это не сбой системы, а честный вывод
«ответ невозможно уверенно оценить» (Рамка §7 и §8, п.6). В карточку попадает
как «не удалось оценить», а не как низкий балл.

## 4. Поток прохождения интервью

Ключевая развилка — что происходит между ответом и следующим вопросом.
Стриминга нет (Р-8), значит кандидат ждёт. Ждать он должен осмысленно.

```
кандидат записал ответ
   │
   ├─► один PUT blob на presigned URL (Р-17)
   │
   └─► POST /answers/{id}/complete
          │
          backend ставит job, отвечает сразу 202
          │
       фронт поллит GET /interviews/{token}/state
          │
       job:  ffmpeg → wav ──► ASR ──► refine ──► оценка ответа
                                                    │
                                          FollowUpGenerator.decide()
                                                    │
                                     ┌──────────────┴──────────────┐
                                  Ask(уточнение)              Proceed
                                     │                             │
                              генерим + TTS                 следующий из плана
                                     │                             │
                                     └──────────► next question ◄──┘
```

Оценка ответа считается здесь же и сохраняется. Финальная карточка её
переиспользует, не пересчитывает — иначе на длинном интервью финал будет
собираться минуты.

Асинхронность: Spring `TaskExecutor` + таблица `processing_job` для видимости
статуса и ретрая. Никаких Kafka и очередей — на одну машину и десяток интервью
это оверинжиниринг, а заказчик и так просил фокус на качестве оценки, не на
инфраструктуре.

**Решено (Р-18).** Поллинг `GET /api/s/{token}` раз в секунду.

## 5. Модель данных

Flyway, `V1__init.sql`. Spring Data JDBC: агрегаты явные, между агрегатами —
ссылки по id (`AggregateReference`), не объектные графы.

```
vacancy ──1:N── requirement
   │
   └──1:N── question_set (иммутабельный, версионированный)
                 └──1:N── question          ← только обязательное ядро

interview ──► vacancy, question_set (снапшот-ссылка)
   │
   ├──1:N── interview_question   ← фактический план: CORE + PERSONAL + FOLLOWUP
   │            └──0:1── answer
   │                       ├──0:1── transcript
   │                       └──0:1── answer_evaluation
   ├──1:N── antifraud_event
   ├──1:N── share_link
   └──0:1── report
```

### Почему план интервью снапшотится

`interview_question` — копия текста вопроса, а не ссылка на `question`.
Причина — Р-13: карточка обязана показывать ровно то, что кандидат видел,
даже если рекрутер потом переписал ядро. `question_set` при этом остаётся
якорем сопоставимости: у всех кандидатов одной версии набора одинаковое ядро.

### Таблицы

| Таблица | Ключевые поля |
|---|---|
| `vacancy` | `title`, `grade`, `description` |
| `requirement` | `vacancy_id`, `text`, `kind` MUST/NICE, `weight`, `is_stop_factor`, `not_verifiable_here` |
| `question_set` | `vacancy_id`, `version`, `frozen_at` |
| `question` | `question_set_id`, `ord`, `text`, `requirement_id`, `strong_signals`, `origin`, `tts_key` |
| `interview` | `vacancy_id`, `question_set_id`, `candidate_name`, `resume_text`, `status`, `consent_at`, `expires_at` |
| `interview_question` | `interview_id`, `ord`, `source` CORE/PERSONAL/FOLLOWUP, `origin_question_id`, `parent_answer_id`, `text`, `requirement_id`, `strong_signals`, `origin`, `tts_key` |
| `answer` | `interview_question_id`, `media_key`, `duration_ms`, `status` |
| `transcript` | `answer_id`, `raw_text`, `refined_text`, `segments` jsonb, `asr_model` |
| `answer_evaluation` | `answer_id`, `scores` jsonb, `quotes` jsonb, `confidence`, `model`, `prompt_version` |
| `report` | `interview_id`, `recommendation`, `overall_score`, `confidence`, `payload` jsonb, `model`, `prompt_version`, `rubric_version` |
| `antifraud_event` | `interview_id`, `type`, `occurred_at` |
| `share_link` | `interview_id`, `role`, `token`, `expires_at`, `revoked_at` |
| `processing_job` | `answer_id`, `kind`, `state`, `attempts`, `last_error` |

`requirement.not_verifiable_here` — из Рамки §3: вакансия должна нести список
навыков, которые этим интервью проверить нельзя. Они уходят в карточку как
«непроверенные», а не как «отсутствующие».

`transcript` хранит **и сырой, и выправленный** текст — Рамка §7 запрещает
незаметно менять историю результата правкой транскрипта.

Версионирование модели и промптов лежит на `answer_evaluation` и `report`
(Рамка §10).

**Решено (Р-19).** jsonb + ручные конвертеры Spring Data JDBC.

## 6. Доступ

Три пути, разные механизмы.

| Кто | Как входит | Что видит |
|---|---|---|
| Рекрутер | Логин | Все свои вакансии и всех кандидатов по ним |
| Кандидат | Токен в ссылке интервью, одноразовое назначение, TTL | Только свою сессию |
| Нанимающий менеджер | Токен в ссылке результата, TTL, отзываемый | Только один конкретный отчёт, read-only |

Разграничение — жёсткое требование Рамки §10 и критерий готовности «Защита
данных». Нанимающий менеджер физически не может получить список кандидатов:
у его токена нет ничего, кроме одного `interview_id`.

**Решено (Р-20).** Spring Security, form login, сессия в куке, пользователи
в памяти. Больше ничего.

## 7. API (набросок)

Рекрутер:

```
POST   /api/vacancies                       создать вакансию с требованиями
POST   /api/vacancies/{id}/questions/generate   сгенерировать ядро → новый question_set
PUT    /api/question-sets/{id}              правка ядра → новая версия (Р-13)
POST   /api/interviews                      вакансия + резюме → персональные вопросы + ссылка
GET    /api/interviews                      список своих
GET    /api/interviews/{id}/report          карточка результата
POST   /api/interviews/{id}/share           выдать ссылку нанимающему
DELETE /api/share-links/{id}                отозвать
```

Кандидат (по токену, без сессии):

```
GET    /api/s/{token}                       состояние: статус, текущий вопрос, прогресс
POST   /api/s/{token}/consent               согласие (Р-3)
POST   /api/s/{token}/answers               начать ответ → presigned URL для чанков
POST   /api/s/{token}/answers/{id}/complete завершить → 202, дальше поллинг
POST   /api/s/{token}/events                антифрод-события (О-8)
```

Нанимающий менеджер:

```
GET    /api/r/{token}                       один отчёт, read-only
```

Разные префиксы (`/api/s/`, `/api/r/`) — чтобы разграничение читалось прямо в
роутинге, а не пряталось в проверках внутри контроллеров.

## 8. Внутренние сервисы

```
POST  asr:8000/transcribe    { media_url } -> { raw_text, segments[{start,end,text}], model }
POST  tts:8001/synthesize    { text }      -> audio/wav
```

`asr` сам скачивает объект из MinIO по presigned URL и сам гоняет ffmpeg —
backend не занимается медиа-конвертацией. `tts` вызывается лениво, с кэшем
в S3 (Р-22).

Таймкоды из `segments` нужны обязательно: Рамка §8 требует подтверждать выводы
цитатой **и таймкодом**, а карточка должна уметь перематывать видео на момент
цитаты. Это заметная часть «объяснимости», ради которой весь кейс.

## 9. Порядок сборки

Демо-сценарий Рамки §15 — это и есть порядок реализации. Сначала сквозной
тонкий путь, потом качество.

| Шаг | Что | Даёт |
|---|---|---|
| 1 | compose, миграции, S3, скелет API | Каркас |
| 2 | Вакансия + требования, `FixedQuestionGenerator` (Р-14) | Есть вопросы без LLM |
| 3 | Прохождение: MediaRecorder → S3 → answer | Есть запись |
| 4 | ASR + refine | Есть транскрипт |
| 5 | Оценка ответа + карточка | **Сквозной путь закрыт** |
| 6 | `LlmQuestionGenerator`: ядро + персональные | Генерация |
| 7 | Адаптивные уточнения (Р-1) | Критерий «Адаптивность» |
| 8 | TTS, доступы, версионирование, устойчивость | Остальные критерии готовности |
| 9 | Фидбэк кандидату (Р-4), антифрод (О-8) | Бонусы |

После шага 5 есть что показывать. Всё после — улучшение, а не риск сорвать демо.
Заказчик прямо сказал: «лучше показать небольшой, но целостный и объяснимый
сценарий, чем большое количество функций без проверяемой логики оценки».

## 10. Риски

| Риск | Митигация |
|---|---|
| ASR плохо берёт RU-техжаргон | `TranscriptRefiner` вторым проходом; fallback на WhisperX; интерфейс `AsrEngine` позволяет менять за конфиг |
| Локальная модель в LM Studio не держит structured output | Проверить на первом же шаге; fallback — жёсткий JSON-промпт + повтор при невалидном ответе |
| Кодеки `MediaRecorder` разошлись между браузерами | ffmpeg-нормализация на стороне `asr` (О-11) |
| Ожидание между вопросами слишком длинное | Локальный демо-стенд, upload мгновенный; при перерастании — стриминг, точка расширения заложена (Р-8) |
| Нет тестовой выборки для метрики 80% | Р-9: показываем методику, а не число |
