# Backend service

The Python backend powers the SavahBot dashboard and the SavahLive Android companion. It is configured for Railway through `railway.json`; the service starts with Uvicorn and exposes `/api/health` as its health-check endpoint.

## Version lines

- **SavahBot:** 2.0.0 — dashboard and backend
- **SavahLive:** 3.0.0 — Android companion version reported to paired phones

The version values are maintained separately in `app/__init__.py` and checked by `scripts/bump_version.py --check`.

## Local development

Install the dependencies from `requirements.txt` (and `requirements-dev.txt` for development), configure the environment using the root documentation, then run the backend test suite as described in [`../docs/TESTING.md`](../docs/TESTING.md).

**Project status:** Active feature development is closed. This guide describes the archived source layout; it is not a claim that every external integration or production deployment has been re-verified in this finalization pass.
