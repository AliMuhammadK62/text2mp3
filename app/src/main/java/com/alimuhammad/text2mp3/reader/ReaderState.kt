package com.alimuhammad.text2mp3.reader

import com.alimuhammad.text2mp3.text.Sentences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ReaderState {

    enum class Status { IDLE, PREPARING, PLAYING, PAUSED, DONE, ERROR }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    @Volatile var spans: List<Sentences.Span> = emptyList()
    @Volatile var sourceText: String = ""
    @Volatile var title: String = ""
    @Volatile var voiceStem: String = ""
    @Volatile var error: String? = null

    @Volatile var pendingText: String? = null

    fun setStatus(s: Status) { _status.value = s }
    fun setIndex(i: Int) { _index.value = i }

    fun reset() {
        _status.value = Status.IDLE
        _index.value = -1
        spans = emptyList()
        sourceText = ""
        title = ""
        error = null
        pendingText = null
    }
}
