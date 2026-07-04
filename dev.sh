#!/usr/bin/env bash
# iris dev tasks (pure bash — the immutable host has no `make`).
#   ./dev.sh check       run all gates (what the pre-commit hook runs)
#   ./dev.sh fmt         auto-format python + rust
#   ./dev.sh lint | typecheck | test | rust-check
#   ./dev.sh setup       enable the tracked git hooks (core.hooksPath)
#
# Lint/typecheck target our own code (src, tests); the formatter runs over
# everything (incl. scripts/) so style is never up for debate.
set -euo pipefail
cd "$(dirname "$0")"

py() { (cd python && uv run "$@"); }

case "${1:-check}" in
  check)
    py ruff format --check .
    py ruff check iris.py src tests
    py mypy
    py pytest
    "$0" rust-check
    ;;
  fmt)
    py ruff format .
    py ruff check --fix iris.py src tests
    (cd rust && cargo fmt)
    ;;
  lint)       py ruff check iris.py src tests ;;
  typecheck)  py mypy ;;
  test)       py pytest ;;
  rust-check)
    (cd rust && cargo fmt --check)
    (cd rust && cargo clippy --all-targets -- -D warnings)
    ;;
  android)
    # gradle runs in the dev toolbox on the toolbox's dnf java (JDK 25); SDK at ~/Android/Sdk
    toolbox run -c dev bash -lc "cd '$PWD/android' && ./gradlew --console=plain testDebugUnitTest"
    ;;
  setup)
    git config core.hooksPath hooks
    echo "git hooks enabled (core.hooksPath=hooks)"
    ;;
  *)
    echo "usage: ./dev.sh {check|fmt|lint|typecheck|test|rust-check|android|setup}" >&2
    exit 2
    ;;
esac
