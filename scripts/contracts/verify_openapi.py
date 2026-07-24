#!/usr/bin/env python3
"""Fail when a Spring MVC controller operation is missing from the checked-in OpenAPI."""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
CONTROLLER_ROOT = ROOT / "services" / "core" / "src" / "main" / "java"
OPENAPI_FILE = ROOT / "packages" / "contracts" / "openapi" / "ai-hotspot-v1.yaml"

HTTP_ANNOTATIONS = {
    "GetMapping": "get",
    "PostMapping": "post",
    "PutMapping": "put",
    "PatchMapping": "patch",
    "DeleteMapping": "delete",
}
ANNOTATION = re.compile(
    r"@(?P<name>RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)"
    r"\s*(?:\((?P<arguments>[^)]*)\))?"
)
QUOTED_VALUE = re.compile(
    r"(?:^|(?:value|path)\s*=\s*)[{\s]*\"(?P<path>/[^\"]*|)\""
)
HTTP_METHODS = {"get", "post", "put", "patch", "delete"}


class UniqueKeyLoader(yaml.SafeLoader):
    """Reject duplicate YAML keys instead of silently keeping the last value."""


def construct_unique_mapping(loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"found duplicate key {key!r}",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    construct_unique_mapping,
)


def normalize_path(base: str, child: str) -> str:
    joined = "/".join(part.strip("/") for part in (base, child) if part.strip("/"))
    return f"/{joined}" if joined else "/"


def annotation_path(arguments: str | None) -> str:
    if not arguments:
        return ""
    match = QUOTED_VALUE.search(arguments)
    return match.group("path") if match else ""


def controller_operations(source: str) -> set[tuple[str, str]]:
    class_position = source.find(" class ")
    if class_position < 0:
        class_position = source.find("\npublic class ")
    prefix = source[:class_position] if class_position >= 0 else ""
    base = ""
    for match in ANNOTATION.finditer(prefix):
        if match.group("name") == "RequestMapping":
            base = annotation_path(match.group("arguments"))

    operations: set[tuple[str, str]] = set()
    body = source[class_position:] if class_position >= 0 else source
    for match in ANNOTATION.finditer(body):
        method = HTTP_ANNOTATIONS.get(match.group("name"))
        if method:
            operations.add((method, normalize_path(base, annotation_path(match.group("arguments")))))
    return operations


def implementation_operations() -> set[tuple[str, str]]:
    operations: set[tuple[str, str]] = set()
    for controller in CONTROLLER_ROOT.rglob("*Controller.java"):
        operations.update(controller_operations(controller.read_text(encoding="utf-8")))
    return operations


def contract_operations() -> set[tuple[str, str]]:
    document = yaml.load(OPENAPI_FILE.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    if not isinstance(document, dict) or not str(document.get("openapi", "")).startswith("3."):
        raise ValueError("OpenAPI document must be a 3.x mapping")
    paths = document.get("paths")
    if not isinstance(paths, dict):
        raise ValueError("OpenAPI document is missing paths")
    operations: set[tuple[str, str]] = set()
    operation_ids: set[str] = set()
    for path, path_item in paths.items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            operations.add((method, path))
            operation_id = operation.get("operationId") if isinstance(operation, dict) else None
            if not isinstance(operation_id, str) or not operation_id:
                raise ValueError(f"{method.upper()} {path} is missing operationId")
            if operation_id in operation_ids:
                raise ValueError(f"duplicate operationId {operation_id!r}")
            operation_ids.add(operation_id)
    validate_local_references(document)
    return operations


def validate_local_references(document: dict[str, object]) -> None:
    references: list[str] = []

    def collect(value: object) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key == "$ref" and isinstance(child, str):
                    references.append(child)
                else:
                    collect(child)
        elif isinstance(value, list):
            for child in value:
                collect(child)

    collect(document)
    for reference in references:
        if not reference.startswith("#/"):
            continue
        current: object = document
        for raw_segment in reference[2:].split("/"):
            segment = raw_segment.replace("~1", "/").replace("~0", "~")
            if not isinstance(current, dict) or segment not in current:
                raise ValueError(f"unresolved local reference {reference!r}")
            current = current[segment]


def main() -> int:
    implementation = implementation_operations()
    contract = contract_operations()
    missing = sorted(implementation - contract, key=lambda item: (item[1], item[0]))
    stale = sorted(contract - implementation, key=lambda item: (item[1], item[0]))
    if missing or stale:
        if missing:
            print("Missing OpenAPI operations:")
            for method, path in missing:
                print(f"  {method.upper():6} {path}")
        if stale:
            print("Stale OpenAPI operations:")
            for method, path in stale:
                print(f"  {method.upper():6} {path}")
        return 1
    print(f"OpenAPI drift check PASS: {len(implementation)} controller operations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
