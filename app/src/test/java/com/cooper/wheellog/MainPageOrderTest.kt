package com.cooper.wheellog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainPageOrderTest {
    private val main = 1
    private val voice = 2
    private val params = 3
    private val events = 4

    @Test
    fun initialPagesPutVoiceChatRightAfterMain() {
        assertThat(MainPageAdapter.initialPages(graph = true, trips = false, events = true))
            .containsExactly(R.layout.main_view_main, R.layout.main_view_voice_chat,
                R.layout.main_view_params_list, R.layout.main_view_graph, R.layout.main_view_events).inOrder()
    }

    @Test
    fun indexAfterAnchorFollowsTheAnchorWhereverItIs() {
        assertThat(MainPageAdapter.indexAfter(listOf(main, voice, params, events), params)).isEqualTo(3)
        assertThat(MainPageAdapter.indexAfter(listOf(main, params), params)).isEqualTo(2)
    }

    @Test
    fun indexAfterMissingAnchorAppends() {
        assertThat(MainPageAdapter.indexAfter(listOf(main, voice), params)).isEqualTo(2)
    }
}
