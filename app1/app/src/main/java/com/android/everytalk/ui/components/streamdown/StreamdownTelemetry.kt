package com.android.everytalk.ui.components.streamdown

object StreamdownTelemetry {
    data class Event(
        val entryPoint: StreamdownEntryPoint,
        val rawLength: Int,
        val displayLength: Int,
        val fallbackReason: String? = null,
    )

    fun interface Sink {
        fun record(event: Event)
    }

    object NoOpSink : Sink {
        override fun record(event: Event) = Unit
    }

    class InMemorySink : Sink {
        val events: MutableList<Event> = mutableListOf()

        override fun record(event: Event) {
            events += event
        }
    }

    fun record(event: Event, sink: Sink = NoOpSink) {
        sink.record(event)
    }
}
