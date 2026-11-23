package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class PbsInputQueueTest {
    private val neutral = PbsProtocol.PadState()
    private val cross = neutral.copy(buttons = PbsProtocol.Button.CROSS.mask)

    @Test fun downAndUpBetweenNetworkPollsAreBothDelivered() {
        val input = PbsInputQueue()
        input.offer(cross, 1)
        input.offer(neutral, 2)
        val down = input.next(10)
        assertEquals(cross, down.state)
        input.acknowledged(down, 12)
        assertEquals(cross, input.next(35).state)
        assertEquals(neutral, input.next(36).state)
    }

    @Test fun repeatedQuickTapsPreserveAReleaseBetweenPresses() {
        val input = PbsInputQueue()
        listOf(cross, neutral, cross, neutral).forEachIndexed { index, state -> input.offer(state, index.toLong()) }
        val observed = (0..3).map { index ->
            input.next(10L + index * 32L).also { input.acknowledged(it, 12L + index * 32L) }.state.buttons
        }
        assertEquals(listOf(cross.buttons, 0, cross.buttons, 0), observed)
    }

    @Test fun sticksUseLatestPositionWhileButtonEdgesAreRetained() {
        val input = PbsInputQueue()
        input.offer(cross.copy(lx = 0), 1)
        input.offer(neutral.copy(lx = 255, ry = 0), 2)
        val sample = input.next(10).state
        assertEquals(cross.buttons, sample.buttons)
        assertEquals(255, sample.lx)
        assertEquals(0, sample.ry)
    }

    @Test fun shortTriggerPressRetainsAnalogPeakAndRelease() {
        val input = PbsInputQueue()
        val trigger = neutral.copy(buttons = PbsProtocol.Button.R2.mask, r2 = 255)
        input.offer(trigger, 1)
        input.offer(neutral, 2)
        val down = input.next(10)
        assertEquals(255, down.state.r2)
        input.acknowledged(down, 11)
        assertEquals(neutral, input.next(40).state)
    }

    @Test fun disableClearsQueuedInputsAndIgnoresLateAcknowledgement() {
        val input = PbsInputQueue()
        input.offer(cross, 1)
        val inFlight = input.next(2)
        input.setEnabled(false)
        input.acknowledged(inFlight, 3)
        assertFalse(input.isCurrent(inFlight))
        assertEquals(neutral, input.next(4).state)
        input.offer(cross, 5)
        input.setEnabled(true)
        assertEquals(neutral, input.next(6).state)
    }

    @Test fun lifecycleClearReleasesImmediatelyWithoutTheMinimumHold() {
        val input = PbsInputQueue()
        input.offer(cross, 1)
        val down = input.next(2)
        input.acknowledged(down, 3)
        input.clear()
        assertEquals(neutral, input.next(4).state)
    }

    @Test fun oldTransitionsAreDiscardedAfterNetworkStall() {
        val input = PbsInputQueue()
        input.offer(cross, 1)
        input.offer(neutral, 2)
        assertEquals(neutral, input.next(200).state)
    }

    @Test fun overflowCoalescesToCurrentIntentInsteadOfReplayingOldPresses() {
        val input = PbsInputQueue(capacity = 4)
        repeat(50) { index -> input.offer(if (index % 2 == 0) cross else neutral, index.toLong()) }
        assertEquals(neutral, input.next(60).state)
        assertEquals(neutral, input.next(92).state)
    }

    @Test fun heldButtonRemainsHeldAcrossHeartbeatPolls() {
        val input = PbsInputQueue()
        input.offer(cross, 1)
        val first = input.next(2)
        input.acknowledged(first, 3)
        assertEquals(cross, input.next(500).state)
        input.offer(neutral, 501)
        assertEquals(neutral, input.next(502).state)
    }

    @Test fun slowAcknowledgementStillGivesTheConsoleAnObservablePress() {
        val input = PbsInputQueue()
        input.offer(cross, 1)
        input.offer(neutral, 2)
        val down = input.next(10)
        input.acknowledged(down, 80)
        assertEquals(cross, input.next(81).state)
        assertEquals(neutral, input.next(104).state)
    }
}
