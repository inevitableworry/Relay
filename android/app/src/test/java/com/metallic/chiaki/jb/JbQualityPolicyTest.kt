package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class JbQualityPolicyTest {
    private val heights = listOf(720, 1080, 1440, 2160)
    private val rate = PbsRateControl(1_000_000,100_000_000,18,35,60_000_000,60_000_000,18,18)
    private fun policy(settings: JbQualityPolicy.Settings = JbQualityPolicy.Settings(true), display: Int = 2160,
                       decoder: Set<Int> = heights.toSet(), controls: PbsRateControl? = rate, audio: Long = 3_072_000) =
        JbQualityPolicy(heights,decoder,display,settings,controls,audio)
    private fun healthy(time: Long) = JbQualityPolicy.Sample(time,60.0,60.0,10,0,8.0,3.072)
    private fun samples(policy: JbQualityPolicy, through: Long, transform: (Long)->JbQualityPolicy.Sample = ::healthy): List<JbQualityPolicy.Decision> =
        (1000L..through step 1000L).mapNotNull { policy.sample(transform(it)) }

    @Test fun startsConservativelyThenProbesOneHigherProfileAfterStablePlayback() {
        val p=policy()
        assertEquals(1080,p.initial(0).height)
        val decisions=samples(p,60_000)
        assertEquals(1,decisions.size)
        assertEquals(1440,decisions.single().request.height)
    }
    @Test fun filtersUnsupportedHardwareAndDisplayInsteadOfTrustingAdvertised4k() {
        val p=policy(display=1500,decoder=setOf(720,1080,1440))
        p.initial(0)
        val decisions=samples(p,180_000)
        assertTrue(decisions.all {it.request.height<=1440})
        assertEquals(1440,decisions.first().request.height)
    }
    @Test fun totalBudgetReservesAudioAndTenPercentTransportAllowance() {
        val p=policy(JbQualityPolicy.Settings(true,maxBandwidthMbps=20))
        val target=p.request(1440)
        assertEquals(15_109_818,target.videoBitrateBps)
        assertTrue((target.videoBitrateBps!!+3_072_000L)*1.10<=20_000_001)
    }
    @Test fun audioOffMakesItsBudgetAvailableToVideo() {
        val p=policy(JbQualityPolicy.Settings(true,maxBandwidthMbps=20),audio=0)
        assertEquals(18_181_818,p.request(1440).videoBitrateBps)
    }
    @Test fun oldServiceCanAutoSwitchResolutionButCannotPretendToApplyBandwidth() {
        val p=policy(controls=null)
        assertNull(p.initial(0).videoBitrateBps)
        assertThrows(IllegalArgumentException::class.java) {
            policy(JbQualityPolicy.Settings(true,maxBandwidthMbps=20),controls=null)
        }
    }
    @Test fun oneSlowSampleDoesNotTriggerReconfiguration() {
        val p=policy();p.initial(0)
        assertTrue(samples(p,30_000){if(it==16_000L) healthy(it).copy(decodedFps=25.0) else healthy(it)}.isEmpty())
    }
    @Test fun persistentDecoderPressureDownshiftsAndDoesNotImmediatelyBounceBack() {
        val p=policy();p.initial(0)
        val decisions=samples(p,60_000){if(it in 12_000L..16_000L) healthy(it).copy(decodedFps=35.0) else healthy(it)}
        assertEquals(1,decisions.size)
        assertEquals(720,decisions.single().request.height)
    }
    @Test fun networkBacklogReducesRealBitrateBeforeReducingResolution() {
        val p=policy();val first=p.initial(0)
        val decisions=samples(p,16_000){healthy(it).copy(lagMs=250)}
        assertEquals(1,decisions.size)
        assertEquals(first.height,decisions.single().request.height)
        assertTrue(decisions.single().request.videoBitrateBps!!<first.videoBitrateBps!!)
    }
    @Test fun fixedResolutionOptimizesOnlyBitrateAfterSustainedPressure() {
        for(height in heights) {
            val p=policy(JbQualityPolicy.Settings(false,manualHeight=height))
            val initial=p.initial(0)
            val decisions=samples(p,120_000){healthy(it).copy(decodedFps=35.0)}
            assertTrue("The automatic bitrate should react at ${height}p", decisions.isNotEmpty())
            assertTrue(decisions.all {it.request.height==height})
            val targets=listOf(initial.videoBitrateBps!!)+decisions.map {it.request.videoBitrateBps!!}
            assertTrue(targets.zipWithNext().all {(before,after)->after<before})
            assertTrue(targets.last()>=rate.minBps)
            assertNull(p.rejectCurrent(121_000))
        }
    }
    @Test fun fixedResolutionIgnoresIsolatedStressAndRecoversWithinItsTotalBudget() {
        val p=policy(JbQualityPolicy.Settings(false,manualHeight=1440,maxBandwidthMbps=20))
        val first=p.initial(0)
        assertTrue(samples(p,20_000){if(it==16_000L) healthy(it).copy(lagMs=500) else healthy(it)}.isEmpty())
        val reduced=(21_000L..25_000L step 1000L).mapNotNull {p.sample(healthy(it).copy(lagMs=250))}.single()
        assertEquals(1440,reduced.request.height)
        assertTrue(reduced.request.videoBitrateBps!!<first.videoBitrateBps!!)
        assertNull(p.sample(healthy(26_000))) // Recovery is not immediate.
        val restored=(27_000L..220_000L step 1000L).mapNotNull {p.sample(healthy(it))}
        assertTrue(restored.isNotEmpty())
        assertTrue(restored.all {it.request.height==1440 && it.request.videoBitrateBps!!<=first.videoBitrateBps!!})
        assertTrue(restored.all {(it.request.videoBitrateBps!!+3_072_000L)*1.10<=20_000_001})
        assertEquals(first.videoBitrateBps,restored.last().request.videoBitrateBps)
    }
    @Test fun fixedResolutionWithExplicitBitrateOptsOutIncludingOnResume() {
        val p=policy(JbQualityPolicy.Settings(false,manualHeight=2160,videoBitrateMbps=30))
        val first=p.initial(0)
        assertEquals(30_000_000,first.videoBitrateBps)
        assertTrue(samples(p,120_000){healthy(it).copy(decodedFps=1.0,frameAgeMs=3000,lagMs=3000)}.isEmpty())
        assertEquals(first,p.resume(first.copy(videoBitrateBps=15_000_000),121_000))
        assertTrue((122_000L..240_000L step 1000L).mapNotNull {p.sample(healthy(it))}.isEmpty())
    }
    @Test fun fixedResolutionOnOldServiceDoesNotAdvertiseFictionalAdaptation() {
        val p=policy(JbQualityPolicy.Settings(false,manualHeight=1080),controls=null)
        assertNull(p.initial(0).videoBitrateBps)
        assertTrue(samples(p,120_000){healthy(it).copy(decodedFps=15.0,lagMs=500)}.isEmpty())
    }
    @Test fun automaticResolutionHonorsAnExplicitRateWhileChangingSize() {
        val p=policy(JbQualityPolicy.Settings(true,videoBitrateMbps=10))
        p.initial(0)
        val down=samples(p,16_000){healthy(it).copy(lagMs=250)}.single()
        assertEquals(720,down.request.height)
        assertEquals(10_000_000,down.request.videoBitrateBps)
    }
    @Test fun backlogReducesTheAlreadyCappedBudgetAndFoldDoesNotIncreaseIt() {
        val p=policy(JbQualityPolicy.Settings(true,maxBandwidthMbps=10))
        val first=p.initial(0)
        val changed=samples(p,16_000){healthy(it).copy(lagMs=250)}.single().request
        assertTrue(changed.videoBitrateBps!!<first.videoBitrateBps!!)
        val folded=policy(JbQualityPolicy.Settings(true,maxBandwidthMbps=10),display=720)
        val resumed=folded.resume(changed,17_000)
        assertEquals(720,resumed.height)
        assertTrue(resumed.videoBitrateBps!!<=changed.videoBitrateBps!!)
    }
    @Test fun startupWarmupAndSuspendedSamplingDoNotCountAsStress() {
        val p=policy();p.initial(0)
        assertTrue(samples(p,10_000){healthy(it).copy(decodedFps=1.0)}.isEmpty())
        assertNull(p.sample(healthy(100_000).copy(decodedFps=1.0)))
        assertNull(p.sample(healthy(101_000).copy(decodedFps=1.0)))
    }
    @Test fun failedHighProfileFallsBackAndTemporarilyRemainsExcluded() {
        val p=policy();p.initial(0)
        assertEquals(1440,samples(p,60_000).single().request.height)
        assertEquals(1080,p.rejectCurrent(61_000)!!.request.height)
        val attempts=(62_000L..180_000 step 1000L).mapNotNull {p.sample(healthy(it))}
        assertTrue(attempts.none {it.request.height>1080})
    }
    @Test fun explicitRateAndQpAreClampedToAdvertisedServerBounds() {
        val limited=rate.copy(maxBps=20_000_000,maxQp=30)
        val p=policy(JbQualityPolicy.Settings(false,manualHeight=2160,videoBitrateMbps=100,minQp=35),controls=limited)
        assertEquals(PbsProfileRequest(2160,20_000_000,30),p.initial(0))
    }
}
