package com.zuicun.agegender;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgeGenderDetector implements AutoCloseable {
    private static final String TAG="AgeGenderLib";
    private static final String VERSION="1.1.0";
    private static final int SIZE=96;
    private static final int[] POINTS={468,473,1,61,291};
    private static final float[] TEMPLATE={32.824f,44.311f,63.027f,44.144f,48.022f,61.488f,35.614f,79.170f,60.626f,79.032f};
    private final AgeGenderConfig config; private final AgeGenderListener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy=new AtomicBoolean();
    private final Nv21FrameConverter converter=new Nv21FrameConverter();
    private final AgeGenderSmoother smoother=new AgeGenderSmoother();
    private final FaceContinuity continuity=new FaceContinuity();
    private final RknnFacePipeline faces; private final RknnSession attributes;
    private volatile long lastSubmit; private volatile boolean closed;

    public AgeGenderDetector(Context context,AgeGenderConfig config,AgeGenderListener listener) {
        if(context==null||config==null||listener==null)throw new IllegalArgumentException("context/config/listener required");
        this.config=config; this.listener=listener;
        faces=RknnFacePipeline.tryCreate(context.getApplicationContext(),1);
        attributes=RknnSession.tryCreate(context.getApplicationContext(),"genderage_rk3588_fp16.rknn",3);
        if(faces==null||attributes==null){if(faces!=null)faces.close();if(attributes!=null)attributes.close();throw new IllegalStateException("RK3588 RKNN models could not be initialized");}
        Log.i(TAG,"initialized: v"+VERSION+" RK3588 NPU");
    }

    public static String getVersion(){return VERSION;}

    public boolean submitNv21(byte[] nv21,int width,int height,long timestampMs) {
        return submitNv21(nv21,width,height,config.rotationDegrees,config.mirrored,timestampMs);
    }

    public boolean submitNv21(byte[] nv21,int width,int height,int rotationDegrees,boolean mirrored,long timestampMs) {
        if(closed||nv21==null||width<=0||height<=0||nv21.length<width*height*3/2)return false;
        if(rotationDegrees!=0&&rotationDegrees!=90&&rotationDegrees!=180&&rotationDegrees!=270)return false;
        long now=SystemClock.uptimeMillis();
        if(now-lastSubmit<config.intervalMs||!busy.compareAndSet(false,true))return false;
        lastSubmit=now;
        final byte[] copy=Arrays.copyOf(nv21,width*height*3/2);
        final int w=width,h=height,r=rotationDegrees; final boolean m=mirrored; final long ts=timestampMs;
        worker.execute(new Runnable(){@Override public void run(){process(copy,w,h,r,m,ts);}});
        return true;
    }

    private void process(byte[] data,int width,int height,int rotation,boolean mirror,long timestamp){
        long started=SystemClock.uptimeMillis(); Bitmap frame=null; AlignedFace aligned=null;
        try {
            frame=converter.convert(data,width,height,rotation,mirror);
            List<List<FaceLandmark>> detected=faces.detect(frame);
            if(detected.isEmpty()){
                smoother.reset(); continuity.reset();
                deliver(new AgeGenderResult(false,-1,-1,0f,0,timestamp,
                        SystemClock.uptimeMillis()-started,AgeGenderResult.STATUS_NO_FACE,0f));
                return;
            }
            aligned=align(frame,detected.get(0));
            if(aligned==null||!aligned.valid){
                float quality=aligned==null?0f:aligned.quality;
                deliver(new AgeGenderResult(true,-1,-1,0f,0,timestamp,
                        SystemClock.uptimeMillis()-started,
                        AgeGenderResult.STATUS_QUALITY_INSUFFICIENT,quality));
                if(config.debugLogging)Log.d(TAG,"quality rejected: "+(aligned==null?"alignment":aligned.diagnostics));
                return;
            }

            if(continuity.updateAndCheckChanged(aligned,timestamp)){
                smoother.reset();
                if(config.debugLogging)Log.i(TAG,"face changed; temporal history cleared");
            }
            int[] pixels=new int[SIZE*SIZE];
            aligned.bitmap.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
            float[] normal=attributes.run(rgb(pixels,false));
            float[] flip=attributes.run(rgb(pixels,true));
            if(normal==null||flip==null||normal.length<3||flip.length<3)throw new IllegalStateException("invalid genderage output");

            float female=(normal[0]+flip[0])*.5f,male=(normal[1]+flip[1])*.5f;
            int gender=male>female?AgeGenderResult.GENDER_MALE:AgeGenderResult.GENDER_FEMALE;
            float max=Math.max(female,male); double a=Math.exp(female-max),b=Math.exp(male-max);
            float confidence=(float)(Math.max(a,b)/(a+b));
            int originalAge=clampAge(normal[2]); int flippedAge=clampAge(flip[2]);
            int age=Math.abs(originalAge-flippedAge)<=6
                    ?Math.round(originalAge*.75f+flippedAge*.25f):originalAge;
            float viewConsistency=clamp01(1f-Math.abs(originalAge-flippedAge)/24f);
            float sampleQuality=aligned.quality*(.85f+.15f*viewConsistency);
            long latency=SystemClock.uptimeMillis()-started;
            AgeGenderResult result=smoother.add(gender,age,confidence,sampleQuality,timestamp,latency);
            deliver(result);
            if(config.debugLogging)Log.d(TAG,"frame="+width+"x"+height+" rot="+rotation
                    +" mirror="+mirror+" "+faces.diagnostics()+" age="+originalAge+"/"+flippedAge
                    +" stable="+result.getAge()+" gender="+gender+" quality="+format(sampleQuality)
                    +" latency="+latency);
        } catch(final Throwable error){
            main.post(new Runnable(){@Override public void run(){if(!closed)listener.onAgeGenderError(error.toString());}});
        } finally {
            if(aligned!=null&&aligned.bitmap!=null)aligned.bitmap.recycle();
            if(frame!=null)frame.recycle(); busy.set(false);
        }
    }

    private void deliver(final AgeGenderResult result){
        main.post(new Runnable(){@Override public void run(){if(!closed)listener.onAgeGenderResult(result);}});
    }

    private static AlignedFace align(Bitmap frame,List<FaceLandmark> landmarks){
        if(landmarks.size()<=473)return null;
        float[] source=new float[10];
        for(int i=0;i<5;i++){
            FaceLandmark point=landmarks.get(POINTS[i]);
            source[i*2]=point.x*frame.getWidth(); source[i*2+1]=point.y*frame.getHeight();
        }
        swap(source,0,2); swap(source,6,8);
        float eyeDistance=distance(source[0],source[1],source[2],source[3]);
        float noseRatio=projection(source[4],source[5],source[0],source[1],source[2],source[3]);
        if(eyeDistance<12f)return new AlignedFace(null,false,0f,0f,0f,eyeDistance,null,"face too small");
        Matrix matrix=similarity(source,TEMPLATE);
        Bitmap output=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output); canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame,matrix,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        ImageQuality iq=measureQuality(output);
        float sizeScore=clamp01((eyeDistance-16f)/42f);
        float poseScore=clamp01(1f-Math.abs(noseRatio-.5f)/.32f);
        float quality=.32f*sizeScore+.28f*poseScore+.20f*iq.sharpnessScore
                +.12f*iq.contrastScore+.08f*iq.exposureScore;
        boolean valid=eyeDistance>=18f&&noseRatio>=.18f&&noseRatio<=.82f
                &&iq.mean>=28f&&iq.mean<=230f&&iq.stdDev>=14f&&iq.gradient>=3.2f;
        float centerX=(source[0]+source[2])*.5f/frame.getWidth();
        float centerY=(source[1]+source[3])*.5f/frame.getHeight();
        byte[] signature=appearanceSignature(output);
        String diagnostics="eye="+format(eyeDistance)+" nose="+format(noseRatio)
                +" light="+format(iq.mean)+" contrast="+format(iq.stdDev)
                +" sharp="+format(iq.gradient)+" score="+format(quality);
        return new AlignedFace(output,valid,quality,centerX,centerY,
                eyeDistance/frame.getWidth(),signature,diagnostics);
    }

    private static ImageQuality measureQuality(Bitmap bitmap){
        int[] pixels=new int[SIZE*SIZE];bitmap.getPixels(pixels,0,SIZE,0,0,SIZE,SIZE);
        double sum=0,sumSquares=0,gradient=0;int gradientCount=0;
        int[] previousRow=new int[SIZE];
        for(int y=0;y<SIZE;y++){
            int previous=-1;
            for(int x=0;x<SIZE;x++){
                int gray=gray(pixels[y*SIZE+x]);sum+=gray;sumSquares+=gray*gray;
                if(previous>=0){gradient+=Math.abs(gray-previous);gradientCount++;}
                if(y>0){gradient+=Math.abs(gray-previousRow[x]);gradientCount++;}
                previous=gray;previousRow[x]=gray;
            }
        }
        float mean=(float)(sum/pixels.length);
        float std=(float)Math.sqrt(Math.max(0d,sumSquares/pixels.length-mean*mean));
        float avgGradient=gradientCount==0?0f:(float)(gradient/gradientCount);
        float exposure=clamp01(1f-Math.abs(mean-128f)/110f);
        return new ImageQuality(mean,std,avgGradient,clamp01(avgGradient/10f),clamp01(std/45f),exposure);
    }

    private static byte[] appearanceSignature(Bitmap bitmap){
        byte[] values=new byte[64];float sum=0f;int index=0;
        for(int gy=0;gy<8;gy++)for(int gx=0;gx<8;gx++){
            int total=0,count=0;
            for(int y=gy*12;y<(gy+1)*12;y+=3)for(int x=gx*12;x<(gx+1)*12;x+=3){total+=gray(bitmap.getPixel(x,y));count++;}
            values[index++]=(byte)(total/count);sum+=total/(float)count;
        }
        float mean=sum/64f;
        for(int i=0;i<values.length;i++)values[i]=(byte)Math.max(-127,Math.min(127,(values[i]&255)-Math.round(mean)));
        return values;
    }

    private static int gray(int pixel){return Math.round(.299f*((pixel>>16)&255)+.587f*((pixel>>8)&255)+.114f*(pixel&255));}
    private static int clampAge(float value){return Math.max(0,Math.min(100,Math.round(value*100f)));}
    private static float clamp01(float value){return Math.max(0f,Math.min(1f,value));}
    private static String format(float value){return String.format(java.util.Locale.US,"%.2f",value);}
    private static float distance(float x1,float y1,float x2,float y2){return (float)Math.hypot(x1-x2,y1-y2);}
    private static float projection(float x,float y,float sx,float sy,float ex,float ey){float dx=ex-sx,dy=ey-sy,den=dx*dx+dy*dy;return den<=0f?.5f:((x-sx)*dx+(y-sy)*dy)/den;}
    private static void swap(float[] p,int a,int b){if(p[a]<=p[b])return;float x=p[a],y=p[a+1];p[a]=p[b];p[a+1]=p[b+1];p[b]=x;p[b+1]=y;}
    private static Matrix similarity(float[] s,float[] d){
        float sx=0,sy=0,dx=0,dy=0;for(int i=0;i<10;i+=2){sx+=s[i];sy+=s[i+1];dx+=d[i];dy+=d[i+1];}
        sx/=5;sy/=5;dx/=5;dy/=5;double den=0,co=0,si=0;
        for(int i=0;i<10;i+=2){double x=s[i]-sx,y=s[i+1]-sy,u=d[i]-dx,v=d[i+1]-dy;den+=x*x+y*y;co+=x*u+y*v;si+=x*v-y*u;}
        float a=(float)(co/den),b=(float)(si/den);Matrix matrix=new Matrix();
        matrix.setValues(new float[]{a,-b,dx-a*sx+b*sy,b,a,dy-b*sx-a*sy,0,0,1});return matrix;
    }
    private static byte[] rgb(int[] pixels,boolean flip){byte[] out=new byte[SIZE*SIZE*3];int k=0;for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){int p=pixels[y*SIZE+(flip?SIZE-1-x:x)];out[k++]=(byte)(p>>16);out[k++]=(byte)(p>>8);out[k++]=(byte)p;}return out;}

    @Override public void close(){closed=true;worker.shutdown();faces.close();attributes.close();}

    private static final class ImageQuality {
        final float mean,stdDev,gradient,sharpnessScore,contrastScore,exposureScore;
        ImageQuality(float mean,float stdDev,float gradient,float sharpness,float contrast,float exposure){this.mean=mean;this.stdDev=stdDev;this.gradient=gradient;this.sharpnessScore=sharpness;this.contrastScore=contrast;this.exposureScore=exposure;}
    }
    private static final class AlignedFace {
        final Bitmap bitmap;final boolean valid;final float quality,centerX,centerY,eyeDistance;final byte[] signature;final String diagnostics;
        AlignedFace(Bitmap bitmap,boolean valid,float quality,float centerX,float centerY,float eyeDistance,byte[] signature,String diagnostics){this.bitmap=bitmap;this.valid=valid;this.quality=quality;this.centerX=centerX;this.centerY=centerY;this.eyeDistance=eyeDistance;this.signature=signature;this.diagnostics=diagnostics;}
    }
    private static final class FaceContinuity {
        private byte[] signature;private float centerX,centerY,eyeDistance;private long timestamp;
        boolean updateAndCheckChanged(AlignedFace face,long currentTimestamp){
            boolean changed=false;
            if(signature!=null){
                float normalizedMove=distance(centerX,centerY,face.centerX,face.centerY)
                        /Math.max(.02f,Math.max(eyeDistance,face.eyeDistance));
                float scaleRatio=Math.max(eyeDistance,face.eyeDistance)/Math.max(1f,Math.min(eyeDistance,face.eyeDistance));
                float appearance=appearanceDistance(signature,face.signature);
                changed=currentTimestamp-timestamp>2500L||normalizedMove>.95f||scaleRatio>1.9f||appearance>34f;
            }
            signature=face.signature;centerX=face.centerX;centerY=face.centerY;eyeDistance=face.eyeDistance;timestamp=currentTimestamp;
            return changed;
        }
        void reset(){signature=null;timestamp=0L;}
        private static float appearanceDistance(byte[] a,byte[] b){float total=0f;for(int i=0;i<a.length;i++)total+=Math.abs(a[i]-b[i]);return total/a.length;}
    }
}
