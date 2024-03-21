package com.example.uistatewithflowtest.repository.message

import com.example.uistatewithflowtest.Reaction
import com.example.uistatewithflowtest.repository.FetchType
import com.example.uistatewithflowtest.repository.RawMessageRepository
import com.example.uistatewithflowtest.repository.Scrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class Message(
    val id: Id,
    val text: String,
    val reaction: Flow<Reaction?>,
    val scrap: Flow<Scrap?>
) {

    data class Id(
        val channelId: Long,
        val messageId: Long
    ): Comparable<Id> {
        override fun compareTo(other: Id): Int {
            val channelIdComparison = this.channelId.compareTo(other.channelId)
            return if (channelIdComparison != 0) {
                channelIdComparison
            } else {
                this.messageId.compareTo(other.messageId)
            }
        }
    }
}

interface MessagesState {
    var awaitInitialization: Boolean
}

@Singleton
class MessageRepository @Inject constructor(
    private val messageFactory: MessageFactory,
    private val rawMessageRepository: RawMessageRepository,
) {

    data class Key(
        val channelId: Long,
        val extraKey: Long?,
    )

    private inner class MessagesStateImpl: MessagesState {
        var pushJob: Job? = null
        override var awaitInitialization: Boolean = false
        val pageManager = PageManager()

        val messages = pageManager.rawMessages
            .map { rawMessages ->
                with(messageFactory) {
                    rawMessages.toMessages()
                }
            }
            .onEach { if (awaitInitialization) it.await() }
    }

    private val messagesStateMap = mutableMapOf<Key, MessagesStateImpl>()

    suspend fun getMessages(key: Key): Flow<List<Message>> {
        val messageState = messagesStateMap[key] ?: run {
            MessagesStateImpl().also {
                it.init(key)
                messagesStateMap[key] = it
            }
        }

        return messageState.messages
    }

    private suspend fun MessagesStateImpl.init(key: Key) {
        pushJob = CoroutineScope(coroutineContext).launch {
            rawMessageRepository.pushes
                .filter { it.id.channelId == key.channelId }
                .collect { pageManager.push(it) }
        }
    }

    private suspend fun List<Message>.await() {
        forEach {
            it.reaction.first()
            it.scrap.first()
        }
    }

    suspend fun init(key: Key, count: Int, around: Long?) {
        val messagesState = messagesStateMap[key]
            ?: throw getGetMessagesNotCalledException(key)
        val page = if (around == null) {
            rawMessageRepository.fetchLatest(key.channelId, count)
        } else {
            rawMessageRepository.fetch(key.channelId, around, count, FetchType.Around)
        }

        messagesState.pageManager.put(page)
    }

    suspend fun fetch(
        key: Key,
        pivot: Long,
        count: Int,
        type: FetchType
    ) {
        val messagesState = messagesStateMap[key]
            ?: throw getGetMessagesNotCalledException(key)
        messagesState.pageManager.put(
            rawMessageRepository.fetch(key.channelId, pivot, count, type)
        )
    }

    suspend fun clear(key: Key) {
        val messagesState = messagesStateMap[key]
            ?: throw getGetMessagesNotCalledException(key)
        messagesState.pageManager.clear()
    }

    fun drop(key: Key) {
        val messagesState = messagesStateMap[key]
            ?: throw getGetMessagesNotCalledException(key)
        messagesState.pushJob?.cancel()
        messagesStateMap.remove(key)
    }

    fun getMessagesState(key: Key): MessagesState {
        return messagesStateMap[key]
            ?: throw getGetMessagesNotCalledException(key)
    }

    private fun getGetMessagesNotCalledException(key: Key): IllegalStateException {
        return IllegalStateException("getMessages() must be called before calling this method for the given key: $key")
    }
}