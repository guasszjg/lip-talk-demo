package com.zuicun.agegender;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/** Quality-weighted temporal filter with age outlier rejection and gender hysteresis. */
final class AgeGenderSmoother {
    private static final int WINDOW_SIZE=7;
    private final Deque<Sample> samples=new ArrayDeque<Sample>();
    private int stableGender=AgeGenderResult.GENDER_UNKNOWN;

    synchronized AgeGenderResult add(int gender,int age,float confidence,float quality,
                                     long timestamp,long latency) {
        float weight=Math.max(.05f,quality)*Math.max(.35f,confidence);
        samples.addLast(new Sample(gender,age,confidence,weight));
        while(samples.size()>WINDOW_SIZE)samples.removeFirst();

        List<Integer> sortedAges=new ArrayList<Integer>();
        for(Sample sample:samples)sortedAges.add(sample.age);
        Collections.sort(sortedAges);
        int median=sortedAges.get(sortedAges.size()/2);
        List<Integer> deviations=new ArrayList<Integer>();
        for(Integer value:sortedAges)deviations.add(Math.abs(value-median));
        Collections.sort(deviations);
        int mad=deviations.get(deviations.size()/2);
        int maxDeviation=Math.max(5,Math.round(mad*2.5f));

        List<Sample> ageSamples=new ArrayList<Sample>();
        for(Sample sample:samples)if(Math.abs(sample.age-median)<=maxDeviation)ageSamples.add(sample);
        Collections.sort(ageSamples,new Comparator<Sample>() {
            @Override public int compare(Sample a,Sample b){return a.age-b.age;}
        });
        float totalAgeWeight=0f; for(Sample sample:ageSamples)totalAgeWeight+=sample.weight;
        float accumulated=0f; int stableAge=median;
        for(Sample sample:ageSamples){accumulated+=sample.weight;if(accumulated>=totalAgeWeight*.5f){stableAge=sample.age;break;}}

        float signed=0f,totalGenderWeight=0f,confidenceSum=0f;
        for(Sample sample:samples){
            if(sample.confidence<.56f)continue;
            float evidence=sample.weight*Math.max(0f,sample.confidence*2f-1f);
            signed+=sample.gender==AgeGenderResult.GENDER_MALE?evidence:-evidence;
            totalGenderWeight+=evidence;
            confidenceSum+=sample.confidence*evidence;
        }
        float normalized=totalGenderWeight<=1e-5f?0f:signed/totalGenderWeight;
        int candidate=normalized>0f?AgeGenderResult.GENDER_MALE:AgeGenderResult.GENDER_FEMALE;
        if(stableGender==AgeGenderResult.GENDER_UNKNOWN){
            Sample latest=samples.peekLast();
            if(Math.abs(normalized)>=.25f && (samples.size()>=2 || latest.confidence>=.75f))stableGender=candidate;
        } else if(candidate!=stableGender && Math.abs(normalized)>=.55f && samples.size()>=3){
            stableGender=candidate;
        }
        float averageConfidence=totalGenderWeight<=1e-5f?.5f:confidenceSum/totalGenderWeight;
        float stableConfidence=.5f+Math.max(0f,averageConfidence-.5f)*Math.abs(normalized);
        return new AgeGenderResult(true,stableGender,stableAge,stableConfidence,
                samples.size(),timestamp,latency,AgeGenderResult.STATUS_OK,quality);
    }

    synchronized void reset(){samples.clear();stableGender=AgeGenderResult.GENDER_UNKNOWN;}

    private static final class Sample {
        final int gender,age; final float confidence,weight;
        Sample(int gender,int age,float confidence,float weight){
            this.gender=gender;this.age=age;this.confidence=confidence;this.weight=weight;
        }
    }
}
