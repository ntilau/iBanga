package com.banga.zip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared progress state between [ArchiveService] and the UI.
 *
 * The service writes into this singleton from [ArchiveService.onStartCommand]
 * and progress callbacks; the activity collects [state] to drive its UI.
 * Using a StateFlow ensures the latest value is available immediately,
 * so a recreated activity picks up the current operation without missing
 * an emission.
 */
object ArchiveProgress {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(
        /** Whether an archive/extract operation is currently in flight. */
        val isRunning: Boolean = false,
        /** "Archiving" or "Extracting" – used in the notification title. */
        val mode: String = "",
        /** Number of entries processed so far. */
        val current: Int = 0,
        /** Total entries to process (0 = indeterminate). */
        val total: Int = 0,
        /** Name of the file currently being processed. */
        val fileName: String = "",
        /** Non-null once the operation has finished (success or failure). */
        val result: String? = null,
        /** Whether the result indicates an error. */
        val isError: Boolean = false,
        /** True while the cancellation request is being processed. */
        val isCancelling: Boolean = false,
    )

    /** Atomically update the current state. */
    fun update(block: (State) -> State) {
        _state.value = block(_state.value)
    }

    /** Reset to the initial (idle) state. */
    fun reset() {
        _state.value = State()
    }
}
