package me.raddatz.veyq.core.terms

import me.raddatz.veyq.core.generated.model.LocalizedMarkdown
import me.raddatz.veyq.core.generated.model.OperatorSection
import me.raddatz.veyq.core.generated.model.AcceptTermsRequest

/** The wire form every terms endpoint serves (`OperatorSection` in `_common.openapi.yaml`). */
fun RenderedSection.toWire(): OperatorSection = OperatorSection(
    revision = revision,
    variant = variant.toWire(),
    text = text.toWire(),
    record = record?.toWire(),
)

// The generator inlines the `$ref`'d OperatorSectionVariant as a nested enum per model; both carry its two names.
fun SectionVariant.toWire(): OperatorSection.Variant = OperatorSection.Variant.valueOf(name)

fun AcceptTermsRequest.Variant.toSection(): SectionVariant = SectionVariant.valueOf(name)

private fun SectionTexts.toWire() = LocalizedMarkdown(de = de, en = en)
