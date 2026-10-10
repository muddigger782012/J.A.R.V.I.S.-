FROM python:3.12-slim
WORKDIR /app
COPY requirements*.txt ./
ARG INSTALL_ASSISTANT_ADAPTERS=false
RUN if [ "$INSTALL_ASSISTANT_ADAPTERS" = "true" ]; then pip install --no-cache-dir -r requirements-assistants.txt; else pip install --no-cache-dir -r requirements.txt; fi
COPY *.py ./
RUN useradd --uid 10001 --create-home jarvis
USER jarvis
EXPOSE 8080
CMD ["sh", "-c", "uvicorn main:app --host 0.0.0.0 --port ${PORT:-8080}"]
