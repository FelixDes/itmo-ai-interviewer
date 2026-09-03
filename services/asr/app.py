"""
Сервис распознавания речи.

Отдельный контейнер, потому что тяжёлые ML-зависимости не место в JVM.
Модель — faster-whisper (CTranslate2): не тянет torch и CUDA, на CPU работает
в разы быстрее реального времени, что важно — кандидат ждёт расшифровку
между вопросами.
"""
import logging
import os
import subprocess
import tempfile

import httpx
from fastapi import FastAPI
from faster_whisper import WhisperModel
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("asr")

MODEL_SIZE = os.getenv("ASR_MODEL", "small")
DEVICE = os.getenv("ASR_DEVICE", "cpu")
COMPUTE_TYPE = os.getenv("ASR_COMPUTE_TYPE", "int8")
LANGUAGE = os.getenv("ASR_LANGUAGE", "ru")
BEAM_SIZE = int(os.getenv("ASR_BEAM_SIZE", "5"))

app = FastAPI(title="aiinterviewer-asr")
model: WhisperModel | None = None


@app.on_event("startup")
def load_model() -> None:
    global model
    log.info("Загружаю модель %s (%s, %s)", MODEL_SIZE, DEVICE, COMPUTE_TYPE)
    model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
    log.info("Модель готова")


class TranscribeRequest(BaseModel):
    media_url: str
    language: str | None = None


class Segment(BaseModel):
    start_ms: int
    end_ms: int
    text: str


class TranscribeResponse(BaseModel):
    text: str
    segments: list[Segment]
    model: str
    usable: bool
    duration_ms: int


@app.get("/health")
def health() -> dict:
    return {"status": "ok" if model else "loading", "model": MODEL_SIZE, "device": DEVICE}


@app.post("/transcribe", response_model=TranscribeResponse)
def transcribe(request: TranscribeRequest) -> TranscribeResponse:
    with tempfile.TemporaryDirectory() as work_dir:
        source = os.path.join(work_dir, "answer.bin")
        wav = os.path.join(work_dir, "answer.wav")

        with httpx.stream("GET", request.media_url, timeout=120.0, follow_redirects=True) as response:
            response.raise_for_status()
            with open(source, "wb") as target:
                for chunk in response.iter_bytes():
                    target.write(chunk)

        # Браузеры пишут в разных контейнерах и кодеках, поэтому нормализуем
        # всё к 16 kHz моно — это то, что ждёт модель
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", source,
             "-ac", "1", "-ar", "16000", "-vn", wav],
            check=True,
        )

        segments, info = model.transcribe(
            wav,
            language=request.language or LANGUAGE,
            beam_size=BEAM_SIZE,
            vad_filter=True,
            condition_on_previous_text=False,
        )

        result: list[Segment] = []
        for segment in segments:
            text = segment.text.strip()
            if text:
                result.append(
                    Segment(
                        start_ms=int(segment.start * 1000),
                        end_ms=int(segment.end * 1000),
                        text=text,
                    )
                )

        full_text = " ".join(s.text for s in result)
        duration_ms = int(info.duration * 1000)

        # Пустая или почти пустая расшифровка — повод честно сказать
        # «оценить нельзя», а не выдавать это за плохой ответ
        usable = len(full_text) >= 10

        log.info(
            "Расшифровано: %d сегментов, %d символов, аудио %.1f с",
            len(result), len(full_text), info.duration,
        )
        return TranscribeResponse(
            text=full_text,
            segments=result,
            model=f"faster-whisper-{MODEL_SIZE}",
            usable=usable,
            duration_ms=duration_ms,
        )
