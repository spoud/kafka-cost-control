FROM python:3.14-slim-trixie
WORKDIR /app

COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/
COPY . /app

RUN uv sync

ENTRYPOINT ["uv", "run", "main.py"]
