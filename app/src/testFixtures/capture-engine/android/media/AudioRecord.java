package android.media;
import java.util.concurrent.CountDownLatch;
public class AudioRecord implements AudioRouting {
 public static final int STATE_INITIALIZED=1,RECORDSTATE_RECORDING=3,READ_NON_BLOCKING=1,ERROR_DEAD_OBJECT=-6,SUCCESS=0;
 public static volatile Scenario scenario;
 public static class Scenario {public int reads=1,samples=32; public boolean observationFailure,stopFailure,releaseFailure;public volatile int accepted,stopCalls,releaseCalls;public final CountDownLatch read=new CountDownLatch(1);}
 private final Scenario s;private final AudioFormat f;private int state;
 private AudioRecord(AudioFormat f){this.f=f;s=scenario;}
 public static int getMinBufferSize(int r,int m,int e){return 256;}
 public int getState(){return STATE_INITIALIZED;}public int getSampleRate(){return f.getSampleRate();}public int getChannelCount(){return f.getChannelCount();}public int getAudioFormat(){return f.getEncoding();}
 public boolean setPreferredDevice(AudioDeviceInfo d){return true;}public AudioDeviceInfo getRoutedDevice(){return new AudioDeviceInfo();}
 public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener l,android.os.Handler h){}public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener l){}
 public void registerAudioRecordingCallback(java.util.concurrent.Executor e,AudioManager.AudioRecordingCallback c){}public void unregisterAudioRecordingCallback(AudioManager.AudioRecordingCallback c){}
 public void startRecording(){state=RECORDSTATE_RECORDING;}public int getRecordingState(){return state;}
 public int read(short[] a,int offset,int length,int mode){if(s.accepted>=s.reads)return 0;for(int i=0;i<s.samples;i++)a[offset+i]=(short)(i+1);s.accepted++;if(s.accepted==s.reads)s.read.countDown();return s.samples;}
 public AudioRecordingConfiguration getActiveRecordingConfiguration(){return s.observationFailure?null:new AudioRecordingConfiguration(f);}
 public int getTimestamp(AudioTimestamp t,int base){return -1;}
 public void stop(){s.stopCalls++;state=0;if(s.stopFailure)throw new IllegalStateException("injected stop failure");}
 public void release(){s.releaseCalls++;if(s.releaseFailure)throw new IllegalStateException("injected release failure");}
 public static class Builder {private AudioFormat f;public Builder setAudioSource(int n){return this;}public Builder setAudioFormat(AudioFormat f){this.f=f;return this;}public Builder setBufferSizeInBytes(int n){return this;}public AudioRecord build(){return new AudioRecord(f);}}
}
