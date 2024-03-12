package com.example.uistatewithflowtest.repository

import com.example.uistatewithflowtest.Reaction
import com.example.uistatewithflowtest.ReactionEvent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactionPullDataSource(
    private val getDelay: Long = 1000,
) {

    suspend fun get(ids: List<Long>): Map<Long, Reaction?> {
        delay(getDelay)
        return ids.associateWith { if ((0 until 2).random() == 0) Reaction.random() else null }
    }
}

interface ReactionPushDataSource {

    val pushEvents: Flow<ReactionEvent>
}

@OptIn(DelicateCoroutinesApi::class)
@Singleton
class RandomReactionPushDataSource(
    private val pushInterval: Long = 2000,
    private val pushTargetIdsRange: LongRange = 0L .. 100L,
): ReactionPushDataSource {

    private val pushEventChannel = Channel<ReactionEvent>()
    override val pushEvents: Flow<ReactionEvent> = pushEventChannel.consumeAsFlow()

    init {
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                delay(pushInterval)
                val targetId = pushTargetIdsRange.random()
                val event = when((0 .. 2).random()) {
                    0 -> ReactionEvent.Insert(targetId, Reaction.random())
                    1 -> ReactionEvent.Update(targetId, Reaction.random())
                    else -> ReactionEvent.Delete(targetId)
                }

                launch { pushEventChannel.send(event) }
            }
        }
    }
}

@Singleton
class ManualReactionPushDataSource @Inject constructor(): ReactionPushDataSource {

    private val pushEventChannel = Channel<ReactionEvent>()
    override val pushEvents: Flow<ReactionEvent> = pushEventChannel.consumeAsFlow()

    suspend fun send(push: ReactionEvent) {
        pushEventChannel.send(push)
    }
}