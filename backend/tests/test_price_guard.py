"""The server's price check must agree with the phone's on every shared case (fixtures/price_guard/cases.json)."""

from __future__ import annotations

import json

import pytest

from app.guard.price_guard import TextBox, check
from tests.conftest import FIXTURES

DOC = json.loads((FIXTURES / "price_guard" / "cases.json").read_text("utf-8"))


@pytest.mark.parametrize("case", DOC["cases"], ids=[c["name"] for c in DOC["cases"]])
def test_shared_case(case: dict) -> None:
    lines = [TextBox(*row) for row in DOC["layouts"][case["layout"]]]
    v = check(lines, case["wanted"], case["summary"])
    e = case["expect"]
    assert v.verdict == e["verdict"]
    if v.verdict == "Ok":
        assert v.option is not None and v.option.name == e["option"]
    elif v.verdict in ("CheaperSameType", "CheaperOtherType"):
        assert v.quoted == e["quoted"]
        assert v.option is not None and (v.option.name, v.option.price) == (e["option"], e["price"])
