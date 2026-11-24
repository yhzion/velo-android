package io.github.yhzion.velo.updates

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateNoticeTest {
    @Test fun onlyTheCurrentOptInMayChangeTheNotice() {
        // A channel or opt-in may have changed away and back while the feed was loading.
        assertEquals(NoticeAction.IgnoreStaleCheck, noticeAction(4, 6, 108, 100, 103))
        assertEquals(NoticeAction.IgnoreStaleCheck, noticeAction(4, 6, null, 100, 103))
        assertEquals(NoticeAction.IgnoreStaleCheck, noticeAction(4, null, 108, 100, 103))
    }

    @Test fun repeatedPollDoesNotResurrectADismissedBuild() {
        assertEquals(NoticeAction.Keep, noticeAction(6, 6, 108, 108, 108))
        assertEquals(NoticeAction.Cancel, noticeAction(6, 6, 108, 108, 0))
    }

    @Test fun retractedOrInstalledReleaseDoesNotLeaveAnObsoleteNotice() {
        assertEquals(NoticeAction.Cancel, noticeAction(6, 6, null, 108, 108))
        // Feed reverted to a build in this release stream which was already announced.
        assertEquals(NoticeAction.Cancel, noticeAction(6, 6, 105, 108, 108))
    }

    @Test fun aPreviouslyUnannouncedStableBuildCanFollowANewerEdgeNotice() {
        assertEquals(NoticeAction.Post, noticeAction(6, 6, 105, 100, 108))
        // Denied posts never advance the watermark, so a later poll can try again.
        assertEquals(NoticeAction.Post, noticeAction(6, 6, 105, 100, 0))
    }
}
