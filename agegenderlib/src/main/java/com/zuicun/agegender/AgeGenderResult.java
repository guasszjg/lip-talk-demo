package com.zuicun.agegender;

public final class AgeGenderResult {
    public static final int GENDER_UNKNOWN=-1, GENDER_FEMALE=0, GENDER_MALE=1;
    public static final int STATUS_OK=0, STATUS_NO_FACE=1, STATUS_QUALITY_INSUFFICIENT=2;
    private final boolean faceDetected;
    private final int gender, age, sampleCount, status;
    private final float confidence, qualityScore;
    private final long timestampMs, latencyMs;

    AgeGenderResult(boolean face, int gender, int age, float confidence,
                    int sampleCount, long timestampMs, long latencyMs) {
        this(face, gender, age, confidence, sampleCount, timestampMs, latencyMs,
                face ? STATUS_OK : STATUS_NO_FACE, face ? 1f : 0f);
    }

    AgeGenderResult(boolean face, int gender, int age, float confidence,
                    int sampleCount, long timestampMs, long latencyMs,
                    int status, float qualityScore) {
        this.faceDetected=face; this.gender=gender; this.age=age;
        this.confidence=confidence; this.sampleCount=sampleCount;
        this.timestampMs=timestampMs; this.latencyMs=latencyMs;
        this.status=status; this.qualityScore=qualityScore;
    }
    public boolean isFaceDetected() { return faceDetected; }
    public boolean isResultReliable() { return status==STATUS_OK && age>=0; }
    public int getStatus() { return status; }
    public String getStatusLabel() {
        if(status==STATUS_NO_FACE)return "无人脸";
        if(status==STATUS_QUALITY_INSUFFICIENT)return "人脸质量不足";
        return "正常";
    }
    public float getQualityScore() { return qualityScore; }
    public int getGender() { return gender; }
    public String getGenderLabel() { return gender==GENDER_MALE ? "男" : gender==GENDER_FEMALE ? "女" : "待稳定"; }
    public int getAge() { return age; }
    public String getAgeBand() { if(age<0)return "--"; if(age<18)return "<18"; if(age>=70)return "70+"; int l=age/10*10; return l+"-"+(l+9); }
    public float getGenderConfidence() { return confidence; }
    public int getSampleCount() { return sampleCount; }
    public long getTimestampMs() { return timestampMs; }
    public long getInferenceLatencyMs() { return latencyMs; }
}
