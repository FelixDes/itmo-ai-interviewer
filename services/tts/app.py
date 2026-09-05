"""
Сервис озвучки вопросов.

Silero TTS: русская речь, работает на CPU быстрее реального времени и не требует
ни ключей, ни интернета после сборки образа.
"""
import html
import io
import logging
import os
import re
import wave

import torch
from fastapi import FastAPI, Response
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tts")

SPEAKER = os.getenv("TTS_SPEAKER", "xenia")
SAMPLE_RATE = int(os.getenv("TTS_SAMPLE_RATE", "24000"))
MODEL_PATH = os.getenv("TTS_MODEL_PATH", "/models/v4_ru.pt")
# Silero не берёт слишком длинный текст за раз, поэтому режем по предложениям
MAX_CHUNK = 800

app = FastAPI(title="aiinterviewer-tts")
model = None


@app.on_event("startup")
def load_model() -> None:
    global model
    torch.set_num_threads(int(os.getenv("TTS_THREADS", "4")))
    log.info("Загружаю Silero из %s", MODEL_PATH)
    model = torch.package.PackageImporter(MODEL_PATH).load_pickle("tts_models", "model")
    model.to(torch.device("cpu"))
    log.info("Модель готова, голос %s", SPEAKER)


class SynthesizeRequest(BaseModel):
    text: str
    speaker: str | None = None
    # Паузы по знакам препинания. Выключается, если текст читается неестественно
    ssml: bool = True


# Длительности пауз в миллисекундах. Вынесены в окружение, потому что
# подбираются на слух: править их приходится не в коде, а слушая результат.
SENTENCE_MS = int(os.getenv("TTS_BREAK_SENTENCE_MS", "500"))
CLAUSE_MS = int(os.getenv("TTS_BREAK_CLAUSE_MS", "350"))
DASH_MS = int(os.getenv("TTS_BREAK_DASH_MS", "250"))
COMMA_MS = int(os.getenv("TTS_BREAK_COMMA_MS", "100"))


def _brk(ms: int) -> str:
    return f"<break time='{ms}ms'/>"


BREAKS = [
    (re.compile(r"([.!?])(\s+)"), r"\1" + _brk(SENTENCE_MS) + r"\2"),
    (re.compile(r"([:;])(\s+)"), r"\1" + _brk(CLAUSE_MS) + r"\2"),
    (re.compile(r"(\s)([—–])(\s)"), r"\1" + _brk(DASH_MS) + r"\2\3"),
    (re.compile(r"(,)(\s+)"), r"\1" + _brk(COMMA_MS) + r"\2"),
]


def to_ssml(text: str) -> str:
    """
    Расставляет паузы по знакам препинания.

    Silero сам почти не делает пауз: вопрос из двух предложений звучит как одно
    длинное, и кандидат теряет, где кончился первый.
    """
    body = html.escape(text, quote=False)
    for pattern, replacement in BREAKS:
        body = pattern.sub(replacement, body)
    return f"<speak>{body}</speak>"


def split_text(text: str) -> list[str]:
    """Режем по границам предложений, чтобы не рвать слова и интонацию."""
    parts: list[str] = []
    current = ""
    for sentence in text.replace("\n", " ").split(". "):
        candidate = f"{current} {sentence}".strip()
        if len(candidate) > MAX_CHUNK and current:
            parts.append(current.strip())
            current = sentence
        else:
            current = candidate
    if current.strip():
        parts.append(current.strip())
    return parts or [text[:MAX_CHUNK]]


@app.get("/health")
def health() -> dict:
    return {"status": "ok" if model else "loading", "speaker": SPEAKER, "sampleRate": SAMPLE_RATE}


@app.get("/voices")
def voices() -> dict:
    """Голоса берём у самой модели, а не из захардкоженного списка."""
    available = [s for s in getattr(model, "speakers", []) if s != "random"]
    return {"default": SPEAKER, "voices": available}


@app.post("/synthesize")
def synthesize(request: SynthesizeRequest) -> Response:
    speaker = request.speaker or SPEAKER
    if speaker not in getattr(model, "speakers", [speaker]):
        log.warning("Неизвестный голос %s, берём %s", speaker, SPEAKER)
        speaker = SPEAKER
    chunks = []
    for part in split_text(request.text):
        audio = None
        if request.ssml:
            # Разметка может не понравиться парсеру модели — тогда читаем как есть,
            # без пауз, но не роняем озвучку целиком
            try:
                audio = model.apply_tts(
                    ssml_text=to_ssml(part), speaker=speaker, sample_rate=SAMPLE_RATE
                )
            except Exception as e:
                log.warning("SSML не разобрался (%s), читаем обычным текстом", e)
        if audio is None:
            audio = model.apply_tts(text=part, speaker=speaker, sample_rate=SAMPLE_RATE)
        chunks.append(audio)

    audio = torch.cat(chunks) if len(chunks) > 1 else chunks[0]
    pcm = (audio.numpy() * 32767).astype("<i2").tobytes()

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm)

    log.info("Озвучено %d символов -> %d байт", len(request.text), buffer.tell())
    return Response(content=buffer.getvalue(), media_type="audio/wav")
