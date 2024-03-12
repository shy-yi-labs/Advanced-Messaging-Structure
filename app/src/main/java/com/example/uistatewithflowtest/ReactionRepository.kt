package com.example.uistatewithflowtest

import com.example.uistatewithflowtest.repository.ReactionPullDataSource
import com.example.uistatewithflowtest.repository.ReactionPushDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class Reaction(val value: Int) {

    companion object {
        fun random(): Reaction {
            return Reaction((0..9).random())
        }
    }

    override fun toString(): String {
        return "Reaction($value)"
    }
}

sealed class ReactionEvent {
    abstract val targetId: Long

    data class Insert(override val targetId: Long, val reaction: Reaction): ReactionEvent()
    data class Update(override val targetId: Long, val reaction: Reaction): ReactionEvent()
    data class Delete(override val targetId: Long): ReactionEvent()

    companion object {

        fun random(targetId: Long): ReactionEvent {
            val eventProviders = listOf<(Long) -> ReactionEvent>(
                { Insert(targetId, Reaction.random()) },
                { Update(targetId, Reaction.random()) },
                { Delete(targetId) },
            )

            return eventProviders.random().invoke(targetId)
        }
    }
}

@Singleton
class ReactionRepository @Inject constructor(
    private val reactionPullDataSource: ReactionPullDataSource,
    private val reactionPushDataSource: ReactionPushDataSource,
) {
    private val mapFlow = OrderedMapFlow<Long, Reaction>()
    private val reactions: Flow<Map<Long, Reaction>> = mapFlow

    suspend fun collectPushes() {
        reactionPushDataSource.pushEvents.collect { event ->
            when(event) {
                is ReactionEvent.Delete -> {
                    mapFlow.delete(event.targetId)
                }
                is ReactionEvent.Insert -> {
                    mapFlow.put(event.targetId, event.reaction)
                }
                is ReactionEvent.Update -> {
                    mapFlow.put(event.targetId, event.reaction)
                }
            }
        }
    }

    fun get(id: Long): Flow<Reaction?> {
        return reactions.map { it[id] }
    }

    suspend fun fetch(ids: List<Long>) {
        CoroutineScope(coroutineContext).launch {
            val pairs = reactionPullDataSource
                .get(ids)
                .mapNotNull { (id, reaction) ->
                    reaction?.let { Pair(id, reaction) }
                }
            mapFlow.putAll(pairs)
        }
    }
}