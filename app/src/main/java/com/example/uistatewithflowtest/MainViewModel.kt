package com.example.uistatewithflowtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.uistatewithflowtest.repository.ReactionRepository
import com.example.uistatewithflowtest.repository.ScrapRepository
import com.example.uistatewithflowtest.repository.RawMessage
import com.example.uistatewithflowtest.repository.RawMessageRepository
import com.example.uistatewithflowtest.repository.Reaction
import com.example.uistatewithflowtest.repository.Scrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val messages: List<Message> = emptyList()
)

data class Message(
    val content: Int,
    val staticValue: String,
    val reaction: Flow<Reaction?>,
    val scrap: Flow<Scrap?>
)

class MessageFactory(
    private val coroutineScope: CoroutineScope,
    private val reactionManager: ReactionManager,
    private val scrapRepository: ScrapRepository
) {
    private val messageCacheStore = mutableMapOf<Int, Pair<RawMessage, Message>>()

    suspend fun Collection<RawMessage>.toItems(): List<Message> {
        val rawMessagesNotInCache = this.filter { rawItem ->
            val cache = messageCacheStore[rawItem.id]
            // Is in cache and rawItem is equal to old rawItem
            return@filter (cache != null && rawItem == cache.first).not()
        }

        reactionManager.fetch(rawMessagesNotInCache.map { it.id })

        rawMessagesNotInCache.forEach {
            messageCacheStore[it.id] = it to it.toItem()
        }

        return this.map { messageCacheStore[it.id]!!.second }
    }

    private fun RawMessage.toItem(): Message {
        return Message(
            content = id,
            staticValue = value,
            reaction = reactionManager.get(id),
            scrap = flow {
                emit(scrapRepository.get(id))
            }.shareIn(coroutineScope, SharingStarted.Eagerly, 1)
        )
    }
}

class MainViewModel : ViewModel() {

    private val rawMessageRepository = RawMessageRepository(30, 3000)
    private val reactionManager = ReactionManager(ReactionRepository(1500, pushTargetIdsRange = 100..110))

    private val messageFactory = MessageFactory(
        viewModelScope,
        reactionManager = reactionManager,
        scrapRepository = ScrapRepository(1000)
    )

    private val rawItemsFlow = OrderedMapFlow<Int, RawMessage>()

    val uiState: StateFlow<MainUiState> = rawItemsFlow
        .map { it.values }
        .map { rawItems ->
            with(messageFactory) {
                MainUiState(messages = rawItems.toItems())
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        viewModelScope.launch {
            rawItemsFlow.putAll(rawMessageRepository.fetchLatest(5).map { Pair(it.id, it) })

            launch {
                rawMessageRepository.pushes.collect {
                    rawItemsFlow.put(it.id, it)
                }
            }

            launch {
                reactionManager.collectPushes()
            }
        }
    }
}