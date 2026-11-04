package android.media; public class AudioFormat {
 public static final int CHANNEL_IN_MONO=16,CHANNEL_IN_STEREO=12,ENCODING_PCM_16BIT=2;
 private int rate,channels,encoding; public int getSampleRate(){return rate;}public int getChannelCount(){return channels;}public int getEncoding(){return encoding;}
 public static class Builder { private final AudioFormat f=new AudioFormat(); public Builder setSampleRate(int n){f.rate=n;return this;}public Builder setChannelMask(int n){f.channels=n==CHANNEL_IN_MONO?1:n==CHANNEL_IN_STEREO?2:0;return this;}public Builder setEncoding(int n){f.encoding=n;return this;}public AudioFormat build(){return f;}}}
