FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# torch ставим отдельно и только CPU-сборку: обычное колесо тянет пакеты CUDA
# на несколько гигабайт, а Silero на процессоре и так быстрее реального времени
RUN pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.5.1

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Модель в образ: без неё первый запрос ждёт скачивания, а на демо интернета может не быть
RUN mkdir -p /models \
    && curl -fsSL -o /models/v4_ru.pt https://models.silero.ai/models/tts/ru/v4_ru.pt

COPY app.py .
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
