# CPU-вариант по умолчанию. GPU-сборка — Dockerfile.gpu.
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Модель кладём в образ, иначе первый запрос ждёт скачивания
ARG ASR_MODEL=small
ENV ASR_MODEL=${ASR_MODEL}
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('${ASR_MODEL}', device='cpu', compute_type='int8')"

COPY app.py .
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
