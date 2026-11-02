package me.raddatz.veyq.memories.testing

import me.raddatz.veyq.core.node.rekey.ScopeRekeyStage
import me.raddatz.veyq.core.testing.ScopeRekeyTestStager
import me.raddatz.veyq.memories.itemfavorites.ItemFavoritesEntity
import me.raddatz.veyq.memories.itemfavorites.ItemFavoritesScopeRekeyParticipant
import io.quarkus.arc.Arc
import io.quarkus.arc.Unremovable
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Singleton

/**
 * Memories' part of `TestScopeRekey.stageAll`: every item-mark row of the scope staged as it stands, under the rotation's
 * re-key id — what the rotator's client sends through `stageScopeItemFavoritesRekey`, re-sealed (ADR 0115 decision 2).
 */
@Singleton
@Unremovable
class MemoriesScopeRekeyTestStager : ScopeRekeyTestStager {
    override fun stageAll(scopeId: String, rekeyId: String) {
        QuarkusTransaction.requiringNew().run {
            val values = ItemFavoritesEntity.list("scopeId", scopeId).associate {
                ScopeRekeyStage.Key(it.markId, ItemFavoritesScopeRekeyParticipant.FIELD) to
                    ScopeRekeyStage.Value(it.ciphertext, ScopeRekeyStage.digest(it.ciphertext))
            }
            Arc.container().instance(ScopeRekeyStage::class.java).get()
                .put(rekeyId, scopeId, ItemFavoritesScopeRekeyParticipant.PART, values)
        }
    }
}
