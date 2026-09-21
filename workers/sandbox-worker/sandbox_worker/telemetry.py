"""Official OpenTelemetry boundary with a deliberately redacted GenAI subset."""

from __future__ import annotations

import os
from contextlib import contextmanager
from typing import Iterator, Mapping

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.propagate import extract
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Span, SpanKind, Status, StatusCode

GEN_AI_SEMCONV_COMMIT = "94f432d7126f5884d30a2cdde6f4e89908ebb6fd"


def configure_telemetry(environment: Mapping[str, str] | None = None) -> None:
    values = environment if environment is not None else os.environ
    if values.get("OTEL_SDK_DISABLED") == "true":
        return
    if values.get("PLATFORM_OBSERVABILITY_OTLP_ENABLED") != "true":
        return
    endpoint = values.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or values.get(
        "PLATFORM_OBSERVABILITY_OTLP_ENDPOINT"
    )
    if not endpoint:
        return
    try:
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

        provider = TracerProvider(
            resource=Resource.create({"service.name": "sandbox-worker"})
        )
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint)))
        trace.set_tracer_provider(provider)
    except Exception:  # noqa: BLE001 - telemetry must never affect execution availability
        return


def extracted_trace_id(headers: Mapping[str, str]) -> str | None:
    span = trace.get_current_span(extract(_lower(headers)))
    span_context = span.get_span_context()
    return format(span_context.trace_id, "032x") if span_context.is_valid else None


@contextmanager
def execute_tool_span(headers: Mapping[str, str], tool_name: str) -> Iterator[Span]:
    tool = _safe(tool_name, "unknown", 120)
    parent: Context = extract(_lower(headers))
    tracer = trace.get_tracer("spaceagent.sandbox", "0.1.0")
    with tracer.start_as_current_span(
        f"execute_tool {tool}",
        context=parent,
        kind=SpanKind.INTERNAL,
        attributes={
            "gen_ai.operation.name": "execute_tool",
            "gen_ai.tool.name": tool,
            "gen_ai.tool.type": "function",
            "spaceagent.sandbox.engine": "docker",
            "spaceagent.gen_ai.semconv.commit": GEN_AI_SEMCONV_COMMIT,
        },
    ) as span:
        try:
            yield span
            span.set_status(Status(StatusCode.OK))
        except Exception as error:
            span.set_attribute("error.type", _safe(type(error).__name__, "_OTHER", 120))
            span.set_status(Status(StatusCode.ERROR))
            raise


def _lower(headers: Mapping[str, str]) -> dict[str, str]:
    return {str(key).lower(): str(value) for key, value in headers.items()}


def _safe(value: str | None, fallback: str, maximum: int) -> str:
    candidate = (value or "").strip().lower()
    if not candidate or len(candidate) > maximum:
        return fallback
    if not all(character.isalnum() or character in "._:/-" for character in candidate):
        return fallback
    return candidate
