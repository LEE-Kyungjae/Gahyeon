FROM python:3.12-slim

ARG PIPER_TTS_VERSION=1.6.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends espeak-ng ffmpeg libespeak-ng1 \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir \
        "piper-tts==${PIPER_TTS_VERSION}" \
        "hunmin==3.12.0" \
        "fastapi==0.116.1" \
        "uvicorn[standard]==0.35.0"

RUN useradd --create-home --uid 10001 piper
WORKDIR /app
COPY server.py /app/server.py
RUN chown -R piper:piper /app

USER piper
EXPOSE 8000

ENTRYPOINT ["python", "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
