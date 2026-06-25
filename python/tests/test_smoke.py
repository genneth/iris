"""Smoke tests. Replace/extend with real sense→lux tests as src/iris grows
(the brightness-estimate math is pure and the natural first thing to TDD)."""


def test_package_importable() -> None:
    import iris  # noqa: F401
