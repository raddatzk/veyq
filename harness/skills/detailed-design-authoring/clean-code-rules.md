### 1. General Design Principles

* Design for **clarity, consistency, and intention-revealing structure**.
* Ensure **strict separation of concerns** (Domain, Ports, Adapters).
* Treat all software units as **black boxes**:
    * Define **responsibility, input, output, and behavior**
    * Do **not** describe internal implementation
* Prefer **simple, composable designs** over complex structures.

### 2. Domain-Driven Structure

* Clearly identify and define:
    * **Domain entities and value objects**
    * **Domain services (pure business logic)**
    * **Ports (interfaces to external systems)**
* Ensure the **domain layer is independent** of infrastructure and frameworks.

### 3. Functional Design Constraints (Domain Layer)

* All domain operations must be:
    * **Side-effect free**
    * **Input → Output transformations**
* No mutable shared state — model all state transitions explicitly.
* Every operation must **return a result** (no void semantics).
* Treat domain behavior as **pure functions over domain types**.

### 4. Data & Type Modeling

* Model **every domain concept explicitly as a type**:
    * No primitive representations (`String`, `int`, etc.) in domain contracts
* Use:
    * **Value objects** for domain values
    * **Identity types** for identifiers
    * **Enums** for finite states
* Define **clear invariants and validation rules** at boundaries.

### 5. Interface (Black Box) Design

For every software unit define:

* **Purpose** (what it does, not how)
* **Inputs** (typed, domain-specific)
* **Outputs** (typed, explicit)
* **Behavior** (business rules, decisions)
* **Error cases** (domain-specific exceptions)

Ensure:

* Interfaces are **minimal and cohesive**
* No leakage of infrastructure concerns
* Names clearly express **intent and outcome**

### 6. Command–Query Separation

* Distinguish clearly between:
    * **Queries:** return data, no side effects
    * **Commands:** represent state transitions, return result
* Avoid ambiguous operations (e.g., “process”, “handle”).

### 7. Error Handling Strategy

* Define all errors as **domain-specific exceptions**
* Specify:
    * When errors occur
    * Why they occur
* Avoid technical or generic exceptions in design

### 8. Collections & Data Access Semantics

* Choose collection types based on **access patterns**:
    * `Map` → key-based lookup
    * `Set` → uniqueness
    * `List` → ordered sequences only when required
* Reflect this explicitly in interface definitions

### 9. Dependency Design

* All dependencies must be:
    * **Explicit**
    * **Inverted via ports**
* Domain depends only on **abstractions**, never implementations

### 10. Naming Conventions

* Use **intention-revealing names**:
    * Nouns for types
    * Verbs for operations
    * Predicates for booleans
* Avoid abbreviations unless universally understood

### 11. Documentation Style (WHY-Focused)

* Every design element must explain:
    * **Why it exists**
    * **Why it is structured this way**
* Avoid describing obvious behavior
* Capture **key design decisions and trade-offs**

### 12. Consistency & Quality

* Ensure **no duplication of domain knowledge (DRY)**
* Keep interfaces **small and focused**
* Design must be:
    * Testable (without infrastructure)
    * Extendable without modification of core logic
* Apply **continuous improvement mindset** (clean, minimal design)

**Key Principle:**  
Your Detailed Design must make a clean, functional, and maintainable implementation the *only natural outcome* — without ever
describing the implementation itself.
