package android.content;
public class Context { public static final String AUDIO_SERVICE="audio"; private final java.io.File files;
 public Context(java.io.File f){files=f;} public java.io.File getFilesDir(){return files;}
 public Object getSystemService(String key){return new android.media.AudioManager();}
 public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}}
