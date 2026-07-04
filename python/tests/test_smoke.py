"""Smoke test: the single-file iris daemon imports cleanly (deps present, no syntax
errors). The pure functions are exercised in test_curve/config/controller/pupil."""


def test_iris_module_importable() -> None:
    import iris  # noqa: F401
