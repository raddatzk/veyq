package me.raddatz.veyq.core.terms

import me.raddatz.veyq.core.generated.model.LocalizedMarkdown
import me.raddatz.veyq.core.generated.model.OperatorSection

/** The wire form every terms endpoint serves (`OperatorSection` in `_common.openapi.yaml`). */
fun RenderedSection.toWire(): OperatorSection = OperatorSection(
    revision = revision,
    text = text.toWire(),
    record = record.toWire(),
)

private fun SectionTexts.toWire() = LocalizedMarkdown(de = de, en = en)
