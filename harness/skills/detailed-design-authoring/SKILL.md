---
name: detailed-design-authoring
description: 'Create or update detailed design artifacts. Use when a detailed design needs to be written e.g. for one building-block boundary, port contract, or interaction.'
---

Before doing anything else, write in the chat "DD Skill has been activated!"
Be concise in the chat and in the document.

## Purpose

Produce one detailed design artifact — an AsciiDoc document with related diagrams — that covers exactly
one design topic (a building-block boundary, a port contract, or an interaction sequence) that describes software units as **black
boxes**, at the level of detail needed for implementation to begin.

## Prerequisites

- **DD-PR-01** The arc42 architecture document (documentation/architecture/src) for this component has been read — starting with 
  the relevant building-block (§5) sections. 
- **DD-PR-02** Read the referenced plantUML diagrams (same filename as the image pngs embedded in the adoc) in
  documentation/architecture/uml, too. 

## Procedure

**DD-P-01 Confirm the topic scope.**
One artifact covers one design topic — do not mix topics in a single artifact. If the BL item
covers more than one topic, stop and propose splitting it into separate BL items before
proceeding.

**DD-P-02 Author the artifact as an AsciiDoc document.**
The artifact file is `documentation/detailed-design/src/<doc-id>_<topic-name>.adoc`. Choose a file name
that identifies the topic and starts with a unique document ID (e.g. `CLCAL-DD-BB-01.2_hash-comparison-orchestrator-port.adoc`).
The IDs uses the format `<COMP>-DD-<TOPIC>` where 

- `COMP` = same component code as used in the architecture 
- `TOPIC`= id of the detailed building block, if this applies or any made up unique topic identifier

Create the `documentation/detailed-design/src/` directory if it does not exist.

**DD-P-03 Document structure**
The adoc document must contain:

- A heading that names the topic.
- Scope: The arc42 ID(s) this artifact refines — building-block ID, port ID, or ADR number — as
  explicit references at the top. These IDs come from the architecture document; do not invent
  IDs for elements that are missing from the architecture. A missing arc42 ID is an
  architecture gap — handle it via DD-P-06.
- Static view detailed down to software units, keeping software units themselves as black boxes. Define the responsibility of every software unit.
- Public interfaces: Identify, specify and document all relevant interfaces for each software unit. The full
  method signature(s), parameter types, and return types.
- Dynamic view if there is non trivial dynamic flow. Not every software unit has a dynamic behavior to be detailed.
- Open Design Questions see DD-P-06

**DD-P-04 Writing rules**

- Be extremely concise. Don't repeat yourself. 
- Don't duplicate content from the architecture or spec, but reference and add details. Detailed design details and
  applies decisions from the architecture — it does not change them. If a design choice here
  would require changing an architecture decision, that is a gap — handle via DD-P-06.
- Use diagrams and representations appropriate for the topic (see DD-P-05).
- When adding prose to a diagram. Don't read out the graphic but add context, background and/or explain decisions you took.
- Be consistent with the architecture when naming elements. 

Check before finalizing the design:

- KISS: Is there unnecessary complexity? Redesign!
- Design for Testability: Make sure that the software units could be blackbox tested. If not, redesign! 


**DD-P-05 Create diagrams where they add clarity.**
Use PlantUML for diagrams. For each diagram:

1. Write the PlantUML source to `documentation/detailed-design/uml/<diagram-name>.puml`.
   Create the directory if it does not exist.
2. Render to PNG: You must run `./gradlew renderDiagrams` in project root.
   The rendered PNG lands in `documentation/detailed-design/images/`.
3. Include the PNG in the adoc document using an `image::` directive referencing the
   `images/` path relative to the adoc file:
   ```asciidoc
   image::../images/<diagram-name>.png[<Caption>]
   ```

A single artifact may contain multiple diagrams of different types (sequence, class, component, activity)
if the topic warrants it. Choose the diagram type that reduces ambiguity for that aspect of
the design. Be lightweight on the amount of documentation and flexible on the artifact formats 
UML, non-UML (e.g. CRC cards — to describe responsibilities and collaborators, business rules, workflows), prose. 

**DD-P-06 Escalate on architecture gaps.**
While authoring, if you discover that:

- An element required by this design (a port, a building block, a dependency) is not described
  in the architecture document, or
- The design as written would require changing an existing ADR or building-block boundary, or
- A significant design decision has to be taken
- A significant improvement would be possible, but requires a change

Surface this to the user and describe the issue, the alternatives, the open questions and your 
recommendation with pros and cons to the Open Design Questions section.

## Design Principles

Your design must enforce Clean Code, Clean Architecture, and Functional Programming principles implicitly — without focusing on
implementation details. See clean-code-rules.md 

## Inline completion checklist

- [ ] All new/changed diagrams have separate `.puml` files in `uml/` and rendered PNGs in `images/`
- [ ] The detailed design is consistent with the architecture
- [ ] Every software unit is blackbox testable
- [ ] The design is as simple as possible
- [ ] Design document is concise but contains everything to implement the right solution
