package com.example.uistatewithflowtest

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.uistatewithflowtest.repository.FetchType
import com.example.uistatewithflowtest.repository.ManualReactionPushDataSource
import com.example.uistatewithflowtest.repository.message.Message
import com.example.uistatewithflowtest.repository.message.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class MainUiState(
    val messages: List<Message> = emptyList()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val manualReactionPushDataSource: ManualReactionPushDataSource,
) : ViewModel() {

    private val around = savedStateHandle.get<Long>(ARG_AROUND)

    private val key = MessageRepository.Key(
        channelId = savedStateHandle.get<Long>(ARG_CHANNEL_ID) ?: 0L,
        extraKey = this.hashCode().toLong()
    )

    val uiState = messageRepository
        .getMessages(key)
        .map { MainUiState(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    val messagesState by lazy { messageRepository.getMessagesState(key) }

    private val isFetchInProgress = AtomicBoolean(false)

    init {
        if (around == null) {
            fetchLatest()
        } else {
            fetch(around, FetchType.Around)
        }
    }

    fun triggerNewReactionEvent(reactionEvent: ReactionEvent) {
        viewModelScope.launch {
            manualReactionPushDataSource.send(reactionEvent)
        }
    }

    fun fetchLatest() {
        viewModelScope.launch {
            messageRepository.fetchLatest(key, MESSAGE_FETCH_COUNT_UNIT)
        }
    }

    fun fetch(pivot: Long, type: FetchType) {
        viewModelScope.launch {
            if (isFetchInProgress.compareAndSet(false, true)) {
                messageRepository.fetch(
                    key = key,
                    pivot = pivot,
                    count = MESSAGE_FETCH_COUNT_UNIT,
                    type = type
                )
                isFetchInProgress.set(false)
            }
        }
    }

    fun clearMessages() {
        viewModelScope.launch(NonCancellable) {
            messageRepository.clear(key)
        }
    }

    fun dropMessages() {
        messageRepository.drop(key)
    }

    override fun onCleared() {
        super.onCleared()

        dropMessages()
    }

    companion object {
        const val ARG_CHANNEL_ID = "argChannelId"
        const val ARG_AROUND = "argAround"

        private val MESSAGE_FETCH_COUNT_UNIT = 20
    }
}