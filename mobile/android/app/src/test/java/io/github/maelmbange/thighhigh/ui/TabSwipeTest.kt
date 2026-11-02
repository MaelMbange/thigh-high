package io.github.maelmbange.thighhigh.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rows that slide to reveal an action, inside pages that slide between tabs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class TabSwipeTest {
    @get:Rule val rule = createComposeRule()

    private var actions = 0
    private lateinit var pager: PagerState

    private fun show(startPage: Int) {
        rule.setContent {
            ThighHighTheme {
                pager = rememberPagerState(initialPage = startPage) { 3 }
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(20) { i ->
                            SwipeToReveal(onAction = { actions++ }) { Text("Page $page ligne $i", Modifier.fillMaxWidth().padding(16.dp)) }
                        }
                    }
                }
            }
        }
    }

    @Test fun swipeRightOnARowGoesToThePreviousTab() {
        show(startPage = 1)
        rule.onRoot().performTouchInput { swipeRight(startX = width * 0.2f, endX = width * 0.9f, durationMillis = 400) }
        rule.waitForIdle()
        assertEquals(0, pager.currentPage)
    }

    @Test fun swipeLeftOnARowOpensItInsteadOfChangingTab() {
        show(startPage = 1)
        rule.onRoot().performTouchInput { swipeLeft(startX = width * 0.9f, endX = width * 0.5f, durationMillis = 400) }
        rule.waitForIdle()
        assertEquals(1, pager.currentPage)
    }

}
