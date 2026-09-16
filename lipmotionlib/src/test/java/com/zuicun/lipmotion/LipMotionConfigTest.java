package com.zuicun.lipmotion;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class LipMotionConfigTest {
    @Test
    public void defaultsAreValid() {
        assertNotNull(LipMotionConfig.builder().build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidFaceCount() {
        LipMotionConfig.builder().setMaximumFaces(0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExcessiveFaceLostGrace() {
        LipMotionConfig.builder().setFaceLostGraceMs(2001L).build();
    }

}
