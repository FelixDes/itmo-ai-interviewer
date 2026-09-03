-- Схема MVP AI-интервьюера. См. docs/architecture.md §5.

create table vacancy (
    id             uuid primary key,
    owner_username varchar(100) not null,
    title          varchar(200) not null,
    grade          varchar(20)  not null,
    description    text         not null default '',
    created_at     timestamptz  not null default now()
);

-- Требования не удаляются физически: на них ссылаются вопросы и старые карточки.
create table requirement (
    id             uuid primary key,
    vacancy_id     uuid         not null references vacancy on delete cascade,
    ord            int          not null,
    text           varchar(500) not null,
    kind           varchar(10)  not null,
    weight         int          not null,
    stop_factor    boolean      not null default false,
    not_verifiable boolean      not null default false,
    deleted        boolean      not null default false
);
create index requirement_vacancy_idx on requirement (vacancy_id);

-- Иммутабельный версионированный набор вопросов ядра (Р-13).
create table question_set (
    id         uuid primary key,
    vacancy_id uuid        not null references vacancy on delete cascade,
    version    int         not null,
    source     varchar(20) not null,
    frozen     boolean     not null default false,
    frozen_at  timestamptz,
    created_at timestamptz not null default now(),
    unique (vacancy_id, version)
);

create table question (
    id              uuid primary key,
    question_set_id uuid        not null references question_set on delete cascade,
    ord             int         not null,
    text            text        not null,
    requirement_id  uuid references requirement,
    strong_signals  jsonb       not null default '[]',
    origin          varchar(20) not null default 'VACANCY',
    unique (question_set_id, ord)
);

create table interview (
    id                   uuid primary key,
    vacancy_id           uuid         not null references vacancy,
    question_set_id      uuid         not null references question_set,
    question_set_version int          not null,
    candidate_name       varchar(200) not null,
    resume_text          text,
    status               varchar(20)  not null,
    candidate_token      varchar(64)  not null unique,
    expires_at           timestamptz  not null,
    consent_at           timestamptz,
    created_at           timestamptz  not null default now(),
    completed_at         timestamptz,
    failure_stage        varchar(50),
    failure_message      text
);
create index interview_vacancy_idx on interview (vacancy_id);

-- Снапшот плана интервью: ровно то, что видел кандидат (Р-13).
create table interview_question (
    id                 uuid primary key,
    interview_id       uuid        not null references interview on delete cascade,
    ord                int         not null,
    kind               varchar(20) not null, -- CORE | PERSONAL | FOLLOWUP
    origin             varchar(20) not null, -- VACANCY | RESUME | PREVIOUS_ANSWER
    origin_question_id uuid,                 -- вопрос ядра, с которого снят снапшот
    parent_question_id uuid references interview_question,
    text               text        not null,
    requirement_id     uuid references requirement,
    strong_signals     jsonb       not null default '[]',
    audio_key          varchar(300),         -- кэш TTS в S3 (Р-22)
    created_at         timestamptz not null default now(),
    unique (interview_id, ord)
);

create table answer (
    id                    uuid primary key,
    interview_question_id uuid        not null unique references interview_question on delete cascade,
    media_key             varchar(300),
    content_type          varchar(100),
    duration_ms           bigint,
    status                varchar(20) not null,
    created_at            timestamptz not null default now(),
    completed_at          timestamptz
);

-- Сырой и выправленный текст храним оба: правка не должна незаметно
-- менять историю результата (Рамка §7).
create table transcript (
    id           uuid primary key,
    answer_id    uuid        not null unique references answer on delete cascade,
    raw_text     text,
    refined_text text,
    segments     jsonb       not null default '[]',
    asr_model    varchar(100),
    created_at   timestamptz not null default now()
);

create table answer_evaluation (
    id             uuid primary key,
    answer_id      uuid        not null unique references answer on delete cascade,
    scores         jsonb       not null default '{}',
    quotes         jsonb       not null default '[]',
    confidence     varchar(10),
    comment        text,
    model          varchar(100),
    prompt_version varchar(50),
    created_at     timestamptz not null default now()
);

create table report (
    id                   uuid primary key,
    interview_id         uuid          not null unique references interview on delete cascade,
    recommendation       varchar(20)   not null,
    overall_score        numeric(3, 1) not null,
    confidence           varchar(10)   not null,
    payload              jsonb         not null,
    model                varchar(100),
    prompt_version       varchar(50),
    rubric_version       varchar(50),
    question_set_version int,
    created_at           timestamptz   not null default now()
);

create table antifraud_event (
    id           uuid primary key,
    interview_id uuid        not null references interview on delete cascade,
    type         varchar(30) not null,
    occurred_at  timestamptz not null default now()
);
create index antifraud_event_interview_idx on antifraud_event (interview_id);

create table share_link (
    id           uuid primary key,
    interview_id uuid        not null references interview on delete cascade,
    token        varchar(64) not null unique,
    expires_at   timestamptz not null,
    revoked_at   timestamptz,
    created_at   timestamptz not null default now()
);
create index share_link_interview_idx on share_link (interview_id);

create table processing_job (
    id           uuid primary key,
    kind         varchar(30) not null, -- ANSWER_PIPELINE | REPORT
    state        varchar(20) not null, -- PENDING | RUNNING | DONE | FAILED
    stage        varchar(30),          -- SAVING | TRANSCRIBING | EVALUATING | PREPARING_NEXT
    interview_id uuid references interview on delete cascade,
    answer_id    uuid references answer on delete cascade,
    attempts     int         not null default 0,
    last_error   text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);
create index processing_job_answer_idx on processing_job (answer_id);
