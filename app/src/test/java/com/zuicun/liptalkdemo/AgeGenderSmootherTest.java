package com.zuicun.liptalkdemo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AgeGenderSmootherTest {
    @Test
    public void usesMedianAgeAndMajorityGender() {
        AgeGenderSmoother smoother = new AgeGenderSmoother();
        smoother.add(7, 1, 31, 0.9f, 8);
        smoother.add(7, 1, 52, 0.8f, 8);
        AgeGenderSmoother.Result result = smoother.add(7, 0, 33, 0.7f, 9);

        assertEquals(1, result.gender);
        assertEquals(33, result.age);
        assertEquals("30–39 岁", result.ageBand());
    }

    @Test
    public void resetsWindowWhenTrackChanges() {
        AgeGenderSmoother smoother = new AgeGenderSmoother();
        smoother.add(1, 1, 60, 0.9f, 8);
        AgeGenderSmoother.Result result = smoother.add(2, 0, 22, 0.9f, 7);

        assertEquals(2, result.trackId);
        assertEquals(22, result.age);
        assertEquals(1, result.sampleCount);
    }

    @Test
    public void ignoresLowConfidenceGenderOutlier() {
        AgeGenderSmoother smoother = new AgeGenderSmoother();
        smoother.add(3, 1, 30, 0.91f, 8);
        smoother.add(3, 1, 32, 0.87f, 8);
        AgeGenderSmoother.Result result = smoother.add(3, 0, 67, 0.51f, 8);

        assertEquals(1, result.gender);
        assertEquals(32, result.age);
    }

    @Test
    public void trimsAgeOutliersInFullWindow() {
        AgeGenderSmoother smoother = new AgeGenderSmoother();
        int[] ages = {31, 32, 33, 34, 35, 2, 92};
        AgeGenderSmoother.Result result = null;
        for (int age : ages) result = smoother.add(4, 0, age, 0.9f, 8);

        assertEquals(33, result.age);
        assertEquals(AgeGenderSmoother.WINDOW_SIZE, result.sampleCount);
    }

    @Test
    public void doesNotInflateWeakSingleSampleConfidence() {
        AgeGenderSmoother.Result result =
                new AgeGenderSmoother().add(5, 1, 30, 0.61f, 8);

        assertEquals(61, result.genderConfidencePercent());
    }

    @Test
    public void returnsUncertainForConflictingGenderEvidence() {
        AgeGenderSmoother smoother = new AgeGenderSmoother();
        smoother.add(6, 1, 30, 0.90f, 8);
        AgeGenderSmoother.Result result = smoother.add(6, 0, 31, 0.86f, 8);

        assertEquals(-1, result.gender);
        assertEquals("不确定", result.genderLabel());
    }
}
