package com.zuicun.liptalkdemo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MultiFaceAnalyzerTest {
    @Test
    public void largerFaceWinsWhenNobodyMovesMouth() {
        MultiFaceAnalyzer analyzer = new MultiFaceAnalyzer();
        MultiFaceAnalyzer.Analysis result = null;
        for (int frame = 0; frame < 8; frame++) {
            result = analyzer.analyze(Arrays.asList(
                    face(0.50f, 0.50f, 0.40f, 0.50f, 0.06f),
                    face(0.25f, 0.45f, 0.20f, 0.25f, 0.06f)
            ), frame * 33L);
        }

        assertNotNull(result);
        assertNotNull(result.selected);
        assertEquals(0.50f, result.selected.centerX, 0.01f);
        assertEquals(2, result.faces.size());
    }

    @Test
    public void movingMouthOverridesLargerSilentFaceAndStaysLocked() {
        MultiFaceAnalyzer analyzer = new MultiFaceAnalyzer();
        float[] openings = {0.05f, 0.08f, 0.17f, 0.07f, 0.22f, 0.06f, 0.19f, 0.07f};
        MultiFaceAnalyzer.Analysis result = null;
        long time = 0L;
        for (float opening : openings) {
            result = analyzer.analyze(Arrays.asList(
                    face(0.55f, 0.50f, 0.42f, 0.50f, 0.06f),
                    face(0.25f, 0.48f, 0.24f, 0.30f, opening)
            ), time += 33L);
        }

        assertNotNull(result);
        assertNotNull(result.selected);
        assertTrue(result.selected.isMoving);
        assertEquals(0.25f, result.selected.centerX, 0.01f);
        int movingTrackId = result.selected.trackId;

        result = analyzer.analyze(Arrays.asList(
                face(0.55f, 0.50f, 0.42f, 0.50f, 0.06f),
                face(0.25f, 0.48f, 0.24f, 0.30f, 0.07f)
        ), time + 100L);
        assertEquals(movingTrackId, result.selected.trackId);
    }

    private static List<NormalizedLandmark> face(
            float centerX,
            float centerY,
            float width,
            float height,
            float openness
    ) {
        List<NormalizedLandmark> points = new ArrayList<>();
        for (int i = 0; i < 478; i++) {
            points.add(NormalizedLandmark.create(centerX, centerY, 0f));
        }
        points.set(0, NormalizedLandmark.create(
                centerX - width / 2f, centerY - height / 2f, 0f));
        points.set(1, NormalizedLandmark.create(
                centerX + width / 2f, centerY + height / 2f, 0f));
        float mouthWidth = width * 0.30f;
        float mouthHeight = mouthWidth * openness;
        points.set(78, NormalizedLandmark.create(
                centerX - mouthWidth / 2f, centerY, 0f));
        points.set(308, NormalizedLandmark.create(
                centerX + mouthWidth / 2f, centerY, 0f));
        points.set(13, NormalizedLandmark.create(
                centerX, centerY - mouthHeight / 2f, 0f));
        points.set(14, NormalizedLandmark.create(
                centerX, centerY + mouthHeight / 2f, 0f));
        return points;
    }
}
