package app.pulso.music

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    @Test fun realSystResponseSuggestsSystemOfADown() {
        val raw = javaClass.getResource("/suggestions-syst.json")!!.readText()
        assertEquals("system of a down", SearchSuggestions.parse(raw).first())
        assertEquals(6, SearchSuggestions.parse(raw).size)
    }
    @Test fun excludesBlankDuplicatesAndUnsupportedQueries() {
        assertEquals(listOf("System", "system of a down"), SearchSuggestions.parse("""["sy",["", "System", "system",["system of a down",0]]]"""))
        assertFalse(SearchSuggestions.eligible("s"))
        assertFalse(SearchSuggestions.eligible("https://youtube.com/watch?v=abc"))
        assertTrue(SearchSuggestions.eligible("syst"))
    }
    @Test fun rapidTypingOnlyRequestsTheFinalPrefix() = runBlocking {
        val requests = mutableListOf<String>()
        val controller = SuggestionController(this, { requests += it; listOf(it) }, 10)
        controller.update("sy"); controller.update("sys"); controller.update("syst")
        withTimeout(2000) { controller.items.first { it == listOf("syst") } }
        assertEquals(listOf("syst"), requests)
        controller.clear(); assertTrue(controller.items.value.isEmpty())
    }
    @Test fun lateCancelledResponseCannotReplaceNewSuggestions() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<List<String>>()
        val controller = SuggestionController(this, { query ->
            if (query == "sys") { oldStarted.complete(Unit); withContext(NonCancellable) { oldResponse.await() } }
            else listOf("metallica")
        }, 0)
        controller.update("sys"); oldStarted.await()
        controller.update("metal")
        withTimeout(2000) { controller.items.first { it == listOf("metallica") } }
        oldResponse.complete(listOf("system of a down")); yield()
        assertEquals(listOf("metallica"), controller.items.value)
        controller.update(""); assertTrue(controller.items.value.isEmpty())
    }
}
