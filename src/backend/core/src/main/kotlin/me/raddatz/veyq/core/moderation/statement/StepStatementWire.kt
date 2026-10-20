package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.generated.model.ServerOwnerStatementInput
import me.raddatz.veyq.core.moderation.cases.SealedNote

/** A Server Owner's step's statement off the wire (`blockAccountSignIn`, `suspendTenant`), shape already bounded by the contract. */
fun ServerOwnerStatementInput.toStepInput(): StepStatementInput = StepStatementInput(
    statementId = statementId.toString(),
    ground = StatementGround.valueOf(ground.name),
    provision = provision,
    facts = facts,
    factsForLog = factsForLog?.let { SealedNote(it.epoch, it.sealedFacts) },
)
