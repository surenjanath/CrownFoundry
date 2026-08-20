# CrownFoundry backend — one container is a complete deployment.
#
# WhiteNoise serves the admin and dashboard static files from inside the app process, so there is
# no second web server to configure. TLS is expected to be terminated by the platform in front of
# this; the settings module reads X-Forwarded-Proto to know that happened.

FROM python:3.13-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencies first, so a source change does not reinstall numpy.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Collected at build time so the running container never writes to its own image. The dummy
# values exist because the settings module refuses to import in production without them, and
# collectstatic needs to import settings.
RUN CROWNFOUNDRY_DEBUG=1 python manage.py collectstatic --noinput

# The engine search is CPU-bound and holds the GIL, so threads would not help; workers do.
# Two per core is a starting point, not a measurement - an AI turn is ~200ms of search.
ENV WEB_CONCURRENCY=3 \
    PORT=8000

EXPOSE 8000

# Migrations run at start rather than at build, because the database does not exist at build time.
# A timeout well above the default absorbs an AI turn that is waiting on a slow Ollama.
CMD ["sh", "-c", "python manage.py migrate --noinput && exec gunicorn crownfoundry.wsgi:application --bind 0.0.0.0:${PORT} --workers ${WEB_CONCURRENCY} --timeout 120 --access-logfile - --error-logfile -"]
