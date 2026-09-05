yf# AI-интервьюер — спецификация API

Версия спеки: **1.0**, 2026-09-03. Контракт для фронтенд-агента.
Менять после старта фронта дорого — все правки только через явное согласование.

Решения-основания: `decisions.md` (Р-17 медиа, Р-18 поллинг, Р-19 jsonb,
Р-20 auth, Р-21 фронт, Р-22 TTS). Экраны: `user-flow.md`.

## 0. Общее

| | |
|---|---|
| Base URL | `http://localhost/api` |
| Формат | JSON, UTF-8. `Content-Type: application/json` |
| Даты | ISO-8601 UTC, `2026-09-03T12:34:56Z` |
| Идентификаторы | UUID v4 строкой |
| CORS | не нужен: nginx отдаёт SPA, `/api` и `/s3` с того же origin |
| Аутентификация | сессионная кука для рекрутера; токен в URL для кандидата и менеджера |

Маршрутизация nginx:

```
/            -> статика SPA
/api/*       -> backend:8080
/s3/*        -> minio:9000   (presigned URL подписываются на http://localhost/s3)
```

Три семейства путей — разграничение читается прямо в роутинге:

| Префикс | Кто | Доступ |
|---|---|---|
| `/api/...` | рекрутер | сессионная кука обязательна |
| `/api/s/{token}/...` | кандидат | токен сессии интервью |
| `/api/r/{token}` | нанимающий менеджер | токен ссылки на результат, read-only |

## 0.1. Разработка фронтенда против скелета

Бэкенд уже отвечает по всем эндпоинтам этой спеки. Внутри — заглушки
(`stub`-пакет), но контракт, статусы, коды ошибок и разграничение доступа
настоящие. Фронт можно писать целиком, ничего не дожидаясь.

### Запуск

```bash
docker compose up -d    # postgres + minio
./gradlew bootRun       # http://localhost:8080
```

### Учётки рекрутеров

| Логин | Пароль |
|---|---|
| `recruiter` | `recruiter` |
| `anna` | `anna` |

Вторая нужна, чтобы проверять разграничение: у неё свои вакансии и она не видит
чужих кандидатов.

### С чего начать

```bash
curl -X POST http://localhost:8080/api/demo/seed
```

Создаёт вакансию «Middle+ Python Developer» с 18 требованиями и зафиксированным
набором из 6 эталонных вопросов. Идемпотентно. Владелец — `recruiter`.

### CORS и прокси

Бэкенд разрешает CORS с любого `localhost:*` вместе с куками, так что Vite
dev-сервер заработает и напрямую. Надёжнее всё же прокси в `vite.config.ts` —
тогда origin один и куки ведут себя как в проде:

```ts
server: { proxy: { "/api": "http://localhost:8080" } }
```

Загрузка медиа идёт **не** через прокси: presigned URL ведёт прямо на
`http://localhost:9000`.

### Что уже настоящее

Всё. Персистентность, разграничение доступа, стейт-машина, версионирование
наборов, presigned-загрузка в MinIO. Распознавание речи — faster-whisper,
озвучка — Silero, вопросы, уточнения, оценка и текст карточки — DeepSeek.

Вердикты по требованиям, итоговый балл и рекомендацию считают правила: числа
должны быть воспроизводимыми, модель их не трогает.

Каждый внешний сервис имеет запасной путь. Нет ASR — заглушка с текстом по теме
вопроса, нет TTS — тишина нужной длительности, нет LLM — правила и эталонный
набор вопросов. Интервью не встаёт ни в одном из случаев, а карточка помечает
такой прогон в `technical.notes`.

### Сколько это занимает

Замерено на живом прогоне: RTX 5070, faster-whisper medium на GPU, DeepSeek v4-flash.

| Операция | Время | Кто ждёт |
|---|---|---|
| Генерация набора вопросов | 40–60 с | Рекрутер, разово на вакансию |
| Создание интервью с резюме | 15–30 с | Рекрутер, разово |
| Озвучка вопроса | 2–3 с, дальше из кэша | Кандидат, один раз на вопрос |
| Пауза между вопросами | 2–13 с | **Кандидат**, на каждый ответ |
| Сборка карточки | 40–90 с | Рекрутер, после интервью |

Полное интервью на 12 вопросов проходится за 65 секунд.

Кандидат ждёт только расшифровку и решение об уточнении. Полная оценка ответа
по рубрике считается в фоне: она нужна карточке, а не следующему вопросу.

Если модель недоступна, всё это работает на правилах и заметно быстрее —
карточка помечает такой прогон в `technical.notes`.

Задержку заглушки распознавания можно менять: `app.asr.stub-delay-ms`
(по умолчанию 2500). Ставьте 0, если ждать надоело.

Карточка честно помечает себя в `technical.notes`: «Резюмирующие формулировки
пока пишет заглушка, а не модель».

## 1. Ошибки

Единый формат для всех ответов 4xx/5xx:

```json
{ "code": "INVALID_STATE", "message": "Интервью уже завершено", "details": null }
```

| HTTP | `code` | Когда |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Невалидное тело. `details` — карта `поле → сообщение` |
| 401 | `UNAUTHENTICATED` | Нет или истекла сессия рекрутера |
| 403 | `FORBIDDEN` | Чужой ресурс |
| 403 | `LINK_REVOKED` | Ссылка менеджера отозвана |
| 404 | `NOT_FOUND` | Нет ресурса или неизвестный токен |
| 409 | `INVALID_STATE` | Операция не разрешена текущим статусом |
| 409 | `QUESTION_SET_FROZEN` | Правка зафиксированного набора |
| 409 | `QUESTION_SET_NOT_FROZEN` | Создание интервью без активного набора |
| 409 | `REPORT_NOT_READY` | Карточка ещё не собрана |
| 410 | `LINK_EXPIRED` | Истёк TTL ссылки |
| 502 | `LLM_UNAVAILABLE` | LLM не ответил |
| 502 | `ASR_UNAVAILABLE` | ASR не ответил |
| 500 | `INTERNAL` | Всё прочее |

## 2. Перечисления

Все значения — константы, регистр важен.

```ts
type Grade = "JUNIOR" | "MIDDLE" | "MIDDLE_PLUS" | "SENIOR" | "LEAD";

/**
 * Кто выносит вердикты по требованиям, ставит балл и рекомендацию.
 * RULES — правила по оценкам ответов, результат воспроизводим.
 * LLM — модель целиком, проще в настройке, но пересчитать результат не по чему.
 */
type EvaluationMode = "RULES" | "LLM";

type RequirementKind = "MUST" | "NICE";

type InterviewStatus =
  | "CREATED"        // ссылка выдана, согласия нет
  | "READY"          // согласие дано, проверка устройств пройдена
  | "IN_PROGRESS"    // интервью идёт
  | "ANALYZING"      // все ответы получены, собирается карточка
  | "READY_REPORT"   // карточка готова
  | "FAILED"         // сборка карточки упала, есть кнопка «повторить»
  | "EXPIRED";       // истёк TTL ссылки кандидата

type QuestionKind = "CORE" | "PERSONAL" | "FOLLOWUP";

type QuestionOrigin = "VACANCY" | "RESUME" | "PREVIOUS_ANSWER";

type QuestionSetSource = "LLM" | "REFERENCE";  // REFERENCE = 6 эталонных вопросов (Р-14)

type ProcessingStage = "SAVING" | "TRANSCRIBING" | "EVALUATING" | "PREPARING_NEXT";

type AnswerStatus = "EVALUATED" | "UNRATEABLE" | "SKIPPED" | "FAILED";

type Recommendation = "FIT" | "NOT_FIT" | "NEEDS_CHECK";

type Confidence = "LOW" | "MEDIUM" | "HIGH";

type RequirementStatus = "CONFIRMED" | "PARTIAL" | "NOT_CONFIRMED" | "NOT_CHECKED";

type EvidenceBasis = "ANSWER" | "RESUME" | "NONE";

type FindingType = "FACT" | "INFERENCE" | "ASSUMPTION";

type AntifraudEventType =
  | "TAB_HIDDEN" | "WINDOW_BLUR" | "COPY" | "PASTE"
  | "MULTIPLE_SCREENS" | "PROMPT_INJECTION";
```

`UNRATEABLE` — не сбой, а честный вывод «ответ невозможно уверенно оценить»
(Рамка §8 п.6). В UI показывается как «не удалось оценить», **не** как ноль баллов.

## 3. Типы

```ts
// ---------- вакансия ----------

interface RequirementInput {
  id?: string;              // есть -> обновить существующее, нет -> создать
  text: string;             // 1..500
  kind: RequirementKind;
  weight: number;           // 1..3
  stopFactor: boolean;
  notVerifiable: boolean;   // навык нельзя проверить этим интервью (Рамка §3)
}

interface Requirement {
  id: string;
  text: string;
  kind: RequirementKind;
  weight: number;
  stopFactor: boolean;
  notVerifiable: boolean;
}

interface VacancyInput {
  title: string;            // 1..200
  grade: Grade;
  description: string;      // 0..20000
  requirements: RequirementInput[];   // 1..40
  // По умолчанию RULES. В режиме LLM веса и стоп-факторы становятся
  // подсказкой для модели, а не формулой расчёта
  evaluationMode: EvaluationMode;
}

interface QuestionSetRef {
  id: string;
  version: number;          // 1, 2, 3...
  frozen: boolean;
  questionCount: number;
}

interface Vacancy {
  id: string;
  title: string;
  grade: Grade;
  description: string;
  requirements: Requirement[];
  activeQuestionSet: QuestionSetRef | null;   // последний зафиксированный
  draftQuestionSet: QuestionSetRef | null;    // незафиксированный, если есть
  evaluationMode: EvaluationMode;
  createdAt: string;
}

interface VacancyListItem {
  id: string;
  title: string;
  grade: Grade;
  interviewCount: number;
  hasFrozenQuestionSet: boolean;
  createdAt: string;
}

// ---------- вопросы ----------

interface QuestionInput {
  id?: string;
  text: string;                    // 1..2000
  requirementId: string | null;    // какое требование проверяет
  strongSignals: string[];         // признаки сильного ответа, 0..10 строк
}

interface Question {
  id: string;
  ord: number;                     // 1-based, порядок показа
  text: string;
  requirementId: string | null;
  strongSignals: string[];
  origin: QuestionOrigin;
}

interface QuestionSet {
  id: string;
  vacancyId: string;
  version: number;
  source: QuestionSetSource;
  frozen: boolean;
  frozenAt: string | null;
  questions: Question[];
  createdAt: string;
}

// ---------- интервью ----------

interface InterviewInput {
  vacancyId: string;
  candidateName: string;      // 1..200
  resumeText?: string | null; // 0..50000, plain text (Р-11)
}

interface ShareLink {
  url: string;                // http://localhost/r/{token}
  token: string;
  expiresAt: string;
  revoked: boolean;
}

interface InterviewListItem {
  id: string;
  candidateName: string;
  status: InterviewStatus;
  recommendation: Recommendation | null;
  overallScore: number | null;
  answered: number;
  planned: number;
  createdAt: string;
  completedAt: string | null;
}

interface InterviewDetail {
  id: string;
  vacancyId: string;
  vacancyTitle: string;
  questionSetVersion: number;
  candidateName: string;
  resumeText: string | null;
  status: InterviewStatus;
  candidateUrl: string;       // http://localhost/s/{token}
  expiresAt: string;
  consentAt: string | null;
  answered: number;
  planned: number;            // ядро + персональные; уточнения увеличивают по ходу
  share: ShareLink | null;
  reportAvailable: boolean;
  failure: { stage: string; message: string } | null;
  createdAt: string;
  completedAt: string | null;
}

// ---------- сессия кандидата ----------

interface CandidateQuestion {
  id: string;
  ord: number;
  kind: QuestionKind;
  text: string;
  audioUrl: string;           // GET, отдаёт audio/mpeg (Р-22)
  requirementText: string | null;  // что проверяем, можно показать кандидату
}

interface CandidateState {
  status: InterviewStatus;
  vacancyTitle: string;
  companyName: string;
  candidateName: string;
  answered: number;
  planned: number;
  expectedDurationMinutes: number;
  maxAnswerDurationSec: number;
  rules: string[];            // готовые строки для экрана C1
  consentText: string;        // плейсхолдер согласия (Р-3)
  antifraudEnabled: boolean;
  currentQuestion: CandidateQuestion | null;
  processing: { answerId: string; stage: ProcessingStage } | null;
  message: string | null;     // текст для EXPIRED / FAILED / завершения
}

interface AnswerUpload {
  answerId: string;
  uploadUrl: string;          // presigned PUT на http://localhost/s3/...
  contentType: string;        // ровно этот заголовок нужен в PUT
  expiresAt: string;
}

// ---------- карточка результата ----------

interface Evidence {
  answerId: string;
  questionOrd: number;
  quote: string;
  startMs: number | null;
  endMs: number | null;
}

interface Finding {
  text: string;
  type: FindingType;          // факт / вывод модели / предположение (Рамка §8 п.4)
  evidence: Evidence[];
}

interface Scores {            // каждая 0..5, null если оценить нельзя
  technicalCorrectness: number | null;
  depth: number | null;
  relevance: number | null;
  example: number | null;
  personalContribution: number | null;
  scaleAndMetrics: number | null;
}

interface RequirementVerdict {
  requirementId: string;
  text: string;
  kind: RequirementKind;
  weight: number;
  stopFactor: boolean;
  status: RequirementStatus;
  basis: EvidenceBasis;
  comment: string;
  evidence: Evidence[];
}

interface AnswerReport {
  answerId: string | null;
  questionId: string;
  ord: number;
  kind: QuestionKind;
  questionText: string;
  requirementId: string | null;
  origin: QuestionOrigin;
  parentQuestionId: string | null;   // для FOLLOWUP — к какому вопросу уточнение
  status: AnswerStatus;
  videoUrl: string | null;           // presigned GET, TTL 1 час
  durationMs: number | null;
  transcriptRefined: string | null;
  transcriptRaw: string | null;
  scores: Scores | null;
  confidence: Confidence | null;
  comment: string | null;
  evidence: Evidence[];
}

interface Report {
  interviewId: string;
  candidateName: string;
  vacancyTitle: string;
  vacancyGrade: Grade;
  completedAt: string;

  recommendation: Recommendation;
  overallScore: number;              // 0..10, один знак после точки
  confidence: Confidence;
  summary: string;

  requirementsMust: RequirementVerdict[];   // раздельно — Рамка §8 п.1
  requirementsNice: RequirementVerdict[];
  answers: AnswerReport[];

  strengths: Finding[];
  risks: Finding[];
  skillsFound: string[];
  skillsNotChecked: string[];
  nextStageQuestions: string[];
  candidateFeedback: string;         // Р-4, рекрутер копирует руками

  technical: {
    antifraudEvents: { type: AntifraudEventType; occurredAt: string }[];
    unrateableAnswers: number;
    failedAnswers: number;
    notes: string[];
  };

  meta: {
    // Каким способом получены вердикты и балл именно в этой карточке
    evaluationMode: EvaluationMode;
    model: string;
    promptVersion: string;
    rubricVersion: string;
    questionSetVersion: number;
    generatedAt: string;
  };
}
```

`requirementsMust` и `requirementsNice` — два поля, а не одно с фильтром по `kind`.
Так фронт физически не сможет их смешать, а Рамка §8 требует раздельной оценки.

`technical` отдельным объектом — Рамка §11 запрещает смешивать антифрод
с профессиональной оценкой. В UI это отдельный блок ниже.

## 4. Эндпоинты рекрутера

### Аутентификация

```
POST /api/auth/login      { username, password }  -> 204 + Set-Cookie
POST /api/auth/logout                             -> 204
GET  /api/auth/me                                 -> { username, displayName }
```

`GET /api/auth/me` отдаёт 401 без сессии — фронт использует его как проверку
при загрузке. Пользователи заданы в конфиге, в памяти (Р-20), таблицы для них нет.

### Вакансии

```
GET  /api/vacancies                -> VacancyListItem[]
POST /api/vacancies    VacancyInput -> 201 Vacancy
GET  /api/vacancies/{id}            -> Vacancy
PUT  /api/vacancies/{id} VacancyInput -> Vacancy
```

`PUT` заменяет список требований целиком. Требования с переданным `id`
обновляются, без `id` — создаются, отсутствующие в запросе — помечаются
удалёнными (мягко), чтобы ссылки из старых карточек продолжали разрешаться.

### Наборы вопросов

```
GET  /api/vacancies/{id}/question-sets                 -> QuestionSet[]   новые первыми
POST /api/vacancies/{id}/question-sets  { source }     -> 201 QuestionSet (draft)
GET  /api/question-sets/{id}                           -> QuestionSet
PUT  /api/question-sets/{id}  { questions: QuestionInput[] } -> QuestionSet
POST /api/question-sets/{id}/revise                    -> 201 QuestionSet (новый draft)
POST /api/question-sets/{id}/freeze                    -> QuestionSet (frozen)
```

Жизненный цикл (Р-13):

```
POST .../question-sets  ──► draft v1 ──PUT──► draft v1 ──freeze──► frozen v1 (активный)
                                                                        │
                                                                      revise
                                                                        ▼
                                                                    draft v2 ──freeze──► frozen v2
```

- `PUT` на зафиксированный набор → `409 QUESTION_SET_FROZEN`. Фронт в этом
  случае предлагает «создать новую версию» и вызывает `revise`.
- `POST .../question-sets` с `source: "LLM"` делает синхронный вызов LLM,
  **до 60 секунд**. Фронт показывает спиннер. `source: "REFERENCE"` — мгновенно.
- `PUT` задаёт порядок вопросов **порядком элементов массива**; `ord` присваивает
  сервер. В `QuestionInput` поля `ord` нет намеренно.
- `freeze` делает набор активным на вакансии. TTS не генерится (Р-22, лениво).

### Интервью

```
POST   /api/interviews          InterviewInput  -> 201 InterviewDetail
GET    /api/interviews?vacancyId={id}           -> InterviewListItem[]
GET    /api/interviews/{id}                     -> InterviewDetail
GET    /api/interviews/{id}/report              -> Report
POST   /api/interviews/{id}/reanalyze           -> 202 InterviewDetail
POST   /api/interviews/{id}/share  { ttlDays? } -> ShareLink
DELETE /api/interviews/{id}/share               -> 204
```

- `POST /api/interviews` требует активный зафиксированный набор, иначе
  `409 QUESTION_SET_NOT_FROZEN`. Сервер копирует ядро в план интервью и, если
  передан `resumeText`, синхронно генерирует персональные вопросы —
  **до 60 секунд**. Без резюме — мгновенно.
- `GET /report` при статусе не `READY_REPORT` → `409 REPORT_NOT_READY`.
- `reanalyze` доступен при `FAILED` и при `READY_REPORT` (перегенерация после
  правки промптов). Ставит статус `ANALYZING`, дальше фронт поллит
  `GET /api/interviews/{id}`.
- `share` без `ttlDays` → 30 дней. Повторный вызов **отзывает старую ссылку**
  и выдаёт новую.
- `ttlDays`: 1..365.

### Демо-данные

```
POST /api/demo/seed  -> { vacancyId, questionSetId }
```

Создаёт вакансию «Middle+ Python Developer» с требованиями и зафиксированным
эталонным набором из 6 вопросов (`Пример вакансии и вопросов.pdf`).
Идемпотентно: повторный вызов возвращает то же самое. Нужен для демо и для
того, чтобы фронт-агент мог работать без ручного заполнения форм.

## 5. Эндпоинты кандидата

Токен из URL `http://localhost/s/{token}`. Сессии и куки не используются.

```
GET  /api/s/{token}                                  -> CandidateState
POST /api/s/{token}/consent                          -> CandidateState
POST /api/s/{token}/start                            -> CandidateState
POST /api/s/{token}/answers   { questionId, contentType } -> AnswerUpload
POST /api/s/{token}/answers/{answerId}/complete { durationMs } -> 202 CandidateState
POST /api/s/{token}/answers/{answerId}/retry-upload  -> AnswerUpload
POST /api/s/{token}/questions/{questionId}/skip      -> CandidateState
POST /api/s/{token}/events  { type, occurredAt? }    -> 204
POST /api/s/{token}/voice   { voice }                -> CandidateState
GET  /api/s/{token}/voices/{voice}/sample            -> 200 audio/wav
GET  /api/s/{token}/questions/{questionId}/audio     -> 200 audio/mpeg
```

Переходы статусов и допустимые вызовы:

| Статус | Что показывает фронт | Что можно вызвать |
|---|---|---|
| `CREATED` | C1: приветствие, правила, чекбокс согласия | `consent` |
| `READY` | C2: проверка камеры и микрофона | `start` |
| `IN_PROGRESS`, `processing == null` | C3: `currentQuestion`, запись | `answers`, `questions/{id}/skip` |
| `IN_PROGRESS`, `processing != null` | C4: ожидание, `processing.stage` | только поллинг |
| `ANALYZING` | C5: «интервью отправлено» | ничего |
| `READY_REPORT`, `FAILED` | C5: «интервью отправлено» | ничего |
| `EXPIRED` | «ссылка недействительна», `message` | ничего |

Вызов не из своей строки таблицы → `409 INVALID_STATE`. Кандидат никогда не
видит различия между `ANALYZING`, `READY_REPORT` и `FAILED` — для него всё это
«интервью отправлено». Так и задумано: результат он не получает.

### Загрузка ответа — точная последовательность

```
1.  MediaRecorder.start()                       локально, без сервера
2.  MediaRecorder.stop() -> Blob                один blob на ответ (Р-17)
3.  POST /api/s/{t}/answers { questionId, contentType: blob.type }
        -> { answerId, uploadUrl, contentType }
4.  PUT uploadUrl
        Content-Type: <ровно contentType из шага 3>
        body: blob
        -> 200 от MinIO
5.  POST /api/s/{t}/answers/{answerId}/complete { durationMs }
        -> 202 CandidateState (status IN_PROGRESS, processing != null)
6.  каждую 1000 мс: GET /api/s/{t}
        пока processing != null
    -> processing == null  =>  currentQuestion уже новый (следующий или уточнение)
       либо status ANALYZING => интервью закончилось
```

Важные детали:

- `contentType` в `PUT` должен **точно совпадать** с полученным из шага 3,
  иначе MinIO отклонит подпись. Фронт не выдумывает свой.
- `answers` вызывается **после** остановки записи, а не до. Presigned URL живёт
  15 минут.
- Пропуск адресуется **вопросом**, а не ответом: в момент пропуска записи ещё нет,
  и `answerId` взять неоткуда. Пропустить можно только текущий вопрос.
- Если `PUT` упал — `retry-upload` даёт новый URL для того же `answerId`.
  Повторный `answers` на тот же вопрос → `409 INVALID_STATE`.
- Шаг 2 создаёт blob целиком в памяти. При лимите 5 минут и битрейте по
  умолчанию это единицы мегабайт — приемлемо.
- `durationMs` фронт берёт из своего таймера. Сервер его не проверяет,
  использует для отображения.

### Поллинг

Интервал **1000 мс**, только когда `processing != null` либо статус `ANALYZING`.
В остальных состояниях не поллим. Бэкофф не нужен — стенд локальный.

Ответ на `GET /api/s/{token}` всегда полный `CandidateState`, а не дельта.
Фронт заменяет состояние целиком. Это сознательно: одно место правды,
никакой синхронизации частичных обновлений.

### Выбор голоса интервьюера

Кандидат выбирает голос на экране проверки устройств. `CandidateState` отдаёт
список `voices` и текущий `voice`; неизвестное значение отклоняется с
`400 VALIDATION_FAILED`.

`voices/{voice}/sample` возвращает короткую фразу этим голосом: выбрать голос
по названию нельзя, его надо услышать.

Менять голос можно и посреди интервью — на оценку он не влияет. Ключ кэша
озвучки включает голос, поэтому после смены кандидат не получит старую запись.

### Аудио вопроса

`GET /api/s/{token}/questions/{questionId}/audio` — `audio/mpeg`, синхронно.
При первом обращении синтезируется и кладётся в S3, дальше отдаётся из кэша
(Р-22). Первый запрос может занять несколько секунд.

Отдаётся с `Cache-Control: public, max-age=86400` — можно ставить прямо в
`<audio src>`.

### Антифрод-события

```
POST /api/s/{token}/events  { "type": "TAB_HIDDEN" }
```

Фронт слушает `visibilitychange`, `blur`, `copy`, `paste` и отправляет событие
fire-and-forget.

`TAB_HIDDEN` и `WINDOW_BLUR` разделены не ради красоты. `visibilitychange`
срабатывает, только когда вкладка перестала быть видимой: переключение вкладок,
сворачивание окна. Если у кандидата несколько мониторов, окно браузера остаётся
видимым при уходе в соседнее приложение, `document.hidden` не меняется, и уход
не фиксируется вообще. Это проверено: переключение окон на трёх мониторах не
давало ни одного события. Поэтому добавлен `blur` окна.

Один уход даёт одно событие: при сворачивании вкладки поднимаются оба события
сразу, считать это двумя нарушениями нечестно. Блюр подтверждается через 400 мс
проверкой `document.hasFocus()` — клик в адресную строку и всплывающее
разрешение браузера тоже снимают фокус, но возвращают его сразу. Ошибку отправки игнорирует — событие не должно ломать интервью.
Отправлять только при `antifraudEnabled == true`; кандидат об этом
предупреждён на экране C1 (требование Рамки §11).

`PROMPT_INJECTION` ставит сервер, а не фронт: в резюме или расшифровке ответа
найдена попытка обратиться к модели напрямую. Детали — `decisions.md`, Р-24.

`MULTIPLE_SCREENS` — обнаружен второй экран. Проверяется через `screen.isExtended`
из Window Management API: свойство не требует разрешения, в отличие от
`getScreenDetails()`. Если браузер его не поддерживает, проверка молча
пропускается — блокировать прохождение из-за неизвестного браузера нельзя.

Событие отправляется на **переход** «экранов стало больше одного», а не на
каждую проверку, иначе карточка утонет в дублях. Пока второй экран подключён,
кандидат не может начать новый ответ; запись, идущая в этот момент, корректно
завершается и отправляется — терять уже сказанное хуже самого нарушения.

## 6. Эндпоинт нанимающего менеджера

```
GET /api/r/{token}  -> Report
```

Тот же `Report`, что у рекрутера. Никаких других эндпоинтов у этой роли нет —
в токене лежит один `interviewId`, получить список кандидатов физически нечем.

Ошибки: `403 LINK_REVOKED`, `410 LINK_EXPIRED`, `404 NOT_FOUND`.

## 7. Что фронт делает сам, без сервера

- проверка камеры и микрофона, превью, индикатор уровня звука, тестовая запись;
- таймер ответа и мягкое предупреждение о лимите `maxAnswerDurationSec`;
- перемотка видео по `startMs` из `Evidence` — обычный `video.currentTime`;
- маппинг `InterviewStatus` в человеческие подписи;
- показ `rules` и `consentText` как готовых строк — сервер их и формулирует,
  чтобы текст не расходился между экранами.

## 8. Стабильность контракта

Что сознательно зафиксировано так, чтобы не менять:

| Решение | Зачем |
|---|---|
| Один `CandidateState` на всю сессию кандидата | Новое поле не ломает фронт; новый эндпоинт — ломает |
| Полное состояние вместо дельт | Нет синхронизации частичных обновлений |
| Один `Report` для рекрутера и менеджера | Одна вёрстка, разный набор кнопок |
| `requirementsMust` / `requirementsNice` отдельно | Их нельзя случайно смешать |
| `technical` отдельным объектом | Антифрод не попадёт в профессиональный балл |
| `ord` присваивает сервер | Нет расхождения порядка между клиентом и БД |
| Все enum строковые | Читаемо в devtools, расширяемо без сдвига чисел |
| Таймкоды в мс числом | Никакого парсинга строк на клиенте |
