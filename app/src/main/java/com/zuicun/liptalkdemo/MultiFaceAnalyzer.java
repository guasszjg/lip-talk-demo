package com.zuicun.liptalkdemo;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains lightweight face tracks, analyzes mouth motion per face and selects one stable target.
 * This is geometry-based tracking for a demo; it does not identify a person's identity.
 */
public final class MultiFaceAnalyzer {
    private static final int TRACK_TTL_FRAMES = 10;
    private static final long SPEAKER_LOCK_MS = 1200L;
    private static final float FALLBACK_SWITCH_MARGIN = 0.12f;

    private final Map<Integer, Track> tracks = new HashMap<>();
    private final float motionThreshold;
    private final float rangeThreshold;
    private int nextTrackId = 1;
    private int frameNumber;
    private Integer selectedTrackId;
    private long lockUntilMs;

    public MultiFaceAnalyzer(DetectionMode mode) {
        if (mode == DetectionMode.SINGLE) {
            motionThreshold = 0.018f;
            rangeThreshold = 0.055f;
        } else {
            // Multi-face landmarks have less temporal smoothing, so use stricter thresholds.
            motionThreshold = 0.024f;
            rangeThreshold = 0.070f;
        }
    }

    public MultiFaceAnalyzer() {
        this(DetectionMode.MULTI);
    }

    public synchronized Analysis analyze(
            List<List<NormalizedLandmark>> faces,
            long timestampMs
    ) {
        frameNumber++;
        List<Observation> observations = new ArrayList<>();
        for (int index = 0; index < faces.size(); index++) {
            Observation observation = Observation.from(index, faces.get(index));
            if (observation != null) observations.add(observation);
        }
        observations.sort(Comparator.comparingDouble((Observation item) -> item.area).reversed());

        Set<Integer> matchedTrackIds = new HashSet<>();
        List<FaceReading> readings = new ArrayList<>();
        for (Observation observation : observations) {
            Track track = findClosestTrack(observation, matchedTrackIds);
            if (track == null) {
                track = new Track(nextTrackId++, motionThreshold, rangeThreshold);
                tracks.put(track.id, track);
            }
            matchedTrackIds.add(track.id);
            readings.add(track.update(observation, frameNumber));
        }
        removeExpiredTracks();

        if (readings.isEmpty()) {
            resetSelection();
            return Analysis.empty();
        }

        float maximumArea = 0f;
        for (FaceReading reading : readings) maximumArea = Math.max(maximumArea, reading.area);
        for (FaceReading reading : readings) reading.calculateScores(maximumArea);

        FaceReading current = findByTrackId(readings, selectedTrackId);
        FaceReading bestMoving = readings.stream()
                .filter(item -> item.isMoving)
                .max(Comparator.comparingDouble(item -> item.speakerScore))
                .orElse(null);
        FaceReading bestFallback = readings.stream()
                .max(Comparator.comparingDouble(item -> item.presenceScore))
                .orElse(readings.get(0));

        FaceReading selected;
        if (current != null && current.isMoving) {
            lockUntilMs = timestampMs + SPEAKER_LOCK_MS;
            selected = current;
        } else if (current != null && timestampMs < lockUntilMs) {
            selected = current;
        } else if (bestMoving != null) {
            selected = bestMoving;
            lockUntilMs = timestampMs + SPEAKER_LOCK_MS;
        } else if (current != null
                && current.presenceScore + FALLBACK_SWITCH_MARGIN >= bestFallback.presenceScore) {
            selected = current;
        } else {
            selected = bestFallback;
            lockUntilMs = 0L;
        }

        selectedTrackId = selected.trackId;
        for (FaceReading reading : readings) reading.isSelected = reading.trackId == selectedTrackId;
        readings.sort(Comparator.comparingInt(item -> item.sourceIndex));
        return new Analysis(readings, selected);
    }

    public synchronized void reset() {
        tracks.clear();
        nextTrackId = 1;
        frameNumber = 0;
        resetSelection();
    }

    private Track findClosestTrack(Observation observation, Set<Integer> alreadyMatched) {
        Track closest = null;
        float closestDistance = Float.MAX_VALUE;
        float matchRadius = Math.max(0.08f, (float) Math.sqrt(observation.area) * 0.55f);
        for (Track track : tracks.values()) {
            if (alreadyMatched.contains(track.id)) continue;
            float distance = distance(observation.centerX, observation.centerY, track.centerX, track.centerY);
            if (distance < matchRadius && distance < closestDistance) {
                closest = track;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private void removeExpiredTracks() {
        Iterator<Track> iterator = tracks.values().iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next();
            if (frameNumber - track.lastSeenFrame > TRACK_TTL_FRAMES) {
                if (selectedTrackId != null && selectedTrackId == track.id) resetSelection();
                iterator.remove();
            }
        }
    }

    private FaceReading findByTrackId(List<FaceReading> readings, Integer trackId) {
        if (trackId == null) return null;
        for (FaceReading reading : readings) {
            if (reading.trackId == trackId) return reading;
        }
        return null;
    }

    private void resetSelection() {
        selectedTrackId = null;
        lockUntilMs = 0L;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static final class Track {
        final int id;
        final MouthMotionTracker motionTracker;
        float centerX;
        float centerY;
        int lastSeenFrame;

        Track(int id, float motionThreshold, float rangeThreshold) {
            this.id = id;
            motionTracker = new MouthMotionTracker(
                    12, 6, motionThreshold, rangeThreshold);
        }

        FaceReading update(Observation observation, int currentFrame) {
            centerX = observation.centerX;
            centerY = observation.centerY;
            lastSeenFrame = currentFrame;
            MouthMotionTracker.Reading motion = motionTracker.update(observation.openness);
            return new FaceReading(
                    observation.sourceIndex,
                    id,
                    observation.landmarks,
                    observation.area,
                    observation.centerX,
                    observation.centerY,
                    motion.openness,
                    motion.movement,
                    motion.isMoving);
        }
    }

    private static final class Observation {
        final int sourceIndex;
        final List<NormalizedLandmark> landmarks;
        final float area;
        final float centerX;
        final float centerY;
        final float openness;

        Observation(
                int sourceIndex,
                List<NormalizedLandmark> landmarks,
                float area,
                float centerX,
                float centerY,
                float openness
        ) {
            this.sourceIndex = sourceIndex;
            this.landmarks = landmarks;
            this.area = area;
            this.centerX = centerX;
            this.centerY = centerY;
            this.openness = openness;
        }

        static Observation from(int sourceIndex, List<NormalizedLandmark> landmarks) {
            if (landmarks.size() <= 454) return null;
            float minX = 1f;
            float minY = 1f;
            float maxX = 0f;
            float maxY = 0f;
            for (NormalizedLandmark point : landmarks) {
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
            float area = Math.max(0f, maxX - minX) * Math.max(0f, maxY - minY);
            NormalizedLandmark upperLip = landmarks.get(13);
            NormalizedLandmark lowerLip = landmarks.get(14);
            NormalizedLandmark leftCorner = landmarks.get(78);
            NormalizedLandmark rightCorner = landmarks.get(308);
            float mouthWidth = distance(leftCorner.x(), leftCorner.y(), rightCorner.x(), rightCorner.y());
            if (area <= 0f || mouthWidth < 0.001f) return null;
            float mouthHeight = distance(upperLip.x(), upperLip.y(), lowerLip.x(), lowerLip.y());
            return new Observation(
                    sourceIndex,
                    landmarks,
                    area,
                    (minX + maxX) / 2f,
                    (minY + maxY) / 2f,
                    mouthHeight / mouthWidth);
        }
    }

    public static final class FaceReading {
        public final int sourceIndex;
        public final int trackId;
        public final List<NormalizedLandmark> landmarks;
        public final float area;
        public final float centerX;
        public final float centerY;
        public final float openness;
        public final float movement;
        public final boolean isMoving;
        public float presenceScore;
        public float speakerScore;
        public boolean isSelected;

        FaceReading(
                int sourceIndex,
                int trackId,
                List<NormalizedLandmark> landmarks,
                float area,
                float centerX,
                float centerY,
                float openness,
                float movement,
                boolean isMoving
        ) {
            this.sourceIndex = sourceIndex;
            this.trackId = trackId;
            this.landmarks = landmarks;
            this.area = area;
            this.centerX = centerX;
            this.centerY = centerY;
            this.openness = openness;
            this.movement = movement;
            this.isMoving = isMoving;
        }

        void calculateScores(float maximumArea) {
            float sizeScore = maximumArea <= 0f ? 0f : area / maximumArea;
            float centerDistance = distance(centerX, centerY, 0.5f, 0.5f) / 0.7071f;
            float centerScore = Math.max(0f, 1f - centerDistance);
            float motionScore = Math.min(1f, movement / 0.050f);
            presenceScore = 0.72f * sizeScore + 0.28f * centerScore;
            speakerScore = 0.45f * presenceScore + 0.55f * motionScore;
            if (isMoving) speakerScore += 0.35f;
        }
    }

    public static final class Analysis {
        public final List<FaceReading> faces;
        public final FaceReading selected;

        Analysis(List<FaceReading> faces, FaceReading selected) {
            this.faces = faces;
            this.selected = selected;
        }

        static Analysis empty() {
            return new Analysis(new ArrayList<>(), null);
        }
    }
}
