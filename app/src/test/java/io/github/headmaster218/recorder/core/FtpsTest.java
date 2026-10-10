package io.github.headmaster218.recorder.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.headmaster218.recorder.core.CaptureTimeline.Epoch;
import io.github.headmaster218.recorder.core.CaptureTimeline.Gap;

/** No sockets/accounts. Synthetic WAVs and scripted connection interfaces only. */
public final class FtpsTest {
    private static int checks, number;
    private static final Path BASE = Paths.get("app/build/ftps-tests/fixtures/run-" + System.nanoTime());
    private static final char[] PASSWORD = "synthetic-test-only".toCharArray();
    private static final FtpsTransfer.Profile PROFILE = new FtpsTransfer.Profile("example.invalid",21,"synthetic","/recorder");
    private static final FtpsTransfer.Guard OPEN = () -> { };
    private interface Action { void run() throws Exception; }
    private static void yes(boolean b) { checks++; if (!b) throw new AssertionError("check " + checks); }
    private static void eq(Object a,Object b) { checks++; if (!a.equals(b)) throw new AssertionError(a+" != "+b+" at "+checks); }
    private static void fails(Action a) throws Exception { checks++; try { a.run(); } catch (IOException | IllegalArgumentException e) { return; } throw new AssertionError("expected rejection "+checks); }
    private static final class Fixture {
        final Path root,wav; final CommittedSegments catalog; final CommittedSegments.Entry entry; final byte[] original;
        Fixture(int frames) throws Exception {
            root=Files.createDirectories(BASE.resolve("case-"+number++));
            try (DirectorySpool s=new DirectorySpool(root,new CachePolicy(1000000,0),new DirectorySpool.NioProtocol(),(op,p)->{})) {
                Epoch epoch=new Epoch("synthetic-ftps","run",0,PcmFormat.DEFAULT,"phone","phone",Gap.START);
                PcmSegmentWriter w=new PcmSegmentWriter(s,epoch,frames,0,out->{});
                byte[] pcm=new byte[frames*2]; for(int i=0;i<pcm.length;i++) pcm[i]=(byte)(i*19);
                w.append(pcm,0,pcm.length); w.stop();
            }
            catalog=new CommittedSegments(root); entry=catalog.page(null).entries.get(0);
            wav=root.resolve(entry.id).resolve("audio.wav"); original=Files.readAllBytes(wav);
        }
        String attempt() { return FtpsTransfer.newAttemptName(entry.id,entry.metadata.wavSha256,"profile-1"); }
        void retained() throws Exception { yes(Files.exists(wav)); yes(Arrays.equals(original,Files.readAllBytes(wav))); }
    }
    private static final class Fake implements FtpsTransfer.Connector {
        final Map<String,byte[]> files=new HashMap<String,byte[]>(); final Set<String> dirs=new HashSet<String>();
        final List<String> commands=new ArrayList<String>();
        int connections,stores,reads,maxWrite,securedData,closedData,closedControl;
        String fault="",current="/recorder"; boolean secure; Data pending; Path mutateSource;
        @Override public FtpsTransfer.Connection connect(String host,int port) throws IOException {
            connections++; eq(PROFILE.host,host); eq(PROFILE.port,port); current="/recorder"; secure=false;
            if(fault.equals("connect")) throw new IOException("synthetic connect failure");
            return new Control();
        }
        final class Control implements FtpsTransfer.Connection {
            final ArrayDeque<Integer> replies=new ArrayDeque<Integer>(); final ByteArrayOutputStream line=new ByteArrayOutputStream();
            Control() { reply(fault.equals("greeting") ? "220 "+repeat('x',1100)+"\r\n" : fault.equals("multiline") ? "220-start\r\n"+repeatLine(900,12) : "220 ready\r\n"); }
            void reply(String s) { for(byte b:s.getBytes(StandardCharsets.US_ASCII)) replies.add(b&255); }
            @Override public InputStream input() { return new InputStream() { @Override public int read() { return replies.isEmpty() ? -1 : replies.remove(); } }; }
            @Override public OutputStream output() { return new OutputStream() {
                @Override public void write(int b) throws IOException {
                    line.write(b); if(b==10) { String s=new String(line.toByteArray(),StandardCharsets.US_ASCII); line.reset(); dispatch(s.trim()); }
                }
            }; }
            void dispatch(String s) throws IOException {
                int space=s.indexOf(' '); String name=space<0?s:s.substring(0,space),arg=space<0?"":s.substring(space+1);
                commands.add(name); if(!name.equals("AUTH")) yes(secure);
                if(name.equals("AUTH")) reply(fault.equals("auth")?"500 no TLS\r\n":"234 continue\r\n");
                else if(name.equals("USER")) reply("331 password\r\n");
                else if(name.equals("PASS")) reply(fault.equals("login")?"530 denied\r\n":"230 okay\r\n");
                else if(name.equals("PBSZ")||name.equals("TYPE")) reply("200 okay\r\n");
                else if(name.equals("PROT")) { eq("P",arg); reply(fault.equals("prot")?"504 unsupported\r\n":"200 protected\r\n"); }
                else if(name.equals("MKD")) reply(dirs.add(arg)?"257 created\r\n":"550 exists\r\n");
                else if(name.equals("CWD")) {
                    if(arg.equals("/recorder")||dirs.contains(arg)) { current=arg; reply("250 changed\r\n"); }
                    else reply("550 absent\r\n");
                } else if(name.equals("EPSV")) reply(fault.equals("epsv")?"229 (|||70000|)\r\n":fault.equals("pasv")?"227 (127,0,0,1,8,8)\r\n":"229 passive (|||2121|)\r\n");
                else if(name.equals("STOR")||name.equals("RETR")) {
                    yes(pending!=null); pending.path=current+"/"+arg; pending.store=name.equals("STOR");
                    if(!pending.store&&!files.containsKey(pending.path)) { reply("550 missing\r\n"); return; }
                    if(pending.store) stores++; else reads++;
                    reply("150 begin\r\n"); if (!fault.equals("lost-completion")) reply(fault.equals("completion")?"451 completion failed\r\n":"226 complete\r\n");
                } else throw new IOException("Unexpected fixture command");
            }
            @Override public void secure(String host) throws IOException { eq(PROFILE.host,host); if(fault.equals("control-tls"))throw new IOException("synthetic trust failure"); secure=true; }
            @Override public FtpsTransfer.Connection openData(int port) { eq(2121,port); yes(secure); pending=new Data(); return pending; }
            @Override public void close() { closedControl++; }
        }
        final class Data implements FtpsTransfer.Connection {
            String path; boolean store,tls,closed; final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            @Override public void secure(String host) throws IOException { eq(PROFILE.host,host); yes(path!=null); if(fault.equals("data-tls"))throw new IOException("synthetic data trust failure"); tls=true; securedData++; }
            @Override public InputStream input() throws IOException {
                yes(tls&&!store); byte[] b=files.get(path).clone();
                if(fault.equals("same-size")&&path.endsWith("audio.wav"))b[44]^=1;
                if(fault.equals("metadata")&&path.endsWith("segment.v1.bin"))b[0]^=1;
                if(fault.equals("marker")&&path.endsWith("delivery.v1.txt"))b[0]^=1;
                if(fault.equals("short"))b=Arrays.copyOf(b,b.length-1);
                if(fault.equals("extra"))b=Arrays.copyOf(b,b.length+1);
                if(fault.equals("unreadable"))throw new IOException("synthetic read denied");
                if(fault.equals("zero-read"))return new InputStream() { @Override public int read(){return 0;} @Override public int read(byte[] b){return 0;} };
                return new ByteArrayInputStream(b);
            }
            @Override public OutputStream output() { yes(tls&&store); return new OutputStream() {
                @Override public void write(int b){bytes.write(b);}
                @Override public void write(byte[] b,int o,int n)throws IOException {
                    maxWrite=Math.max(maxWrite,n); if(fault.equals("write")){bytes.write(b,o,1);throw new IOException("synthetic partial write");} bytes.write(b,o,n);
                }
            }; }
            @Override public FtpsTransfer.Connection openData(int port)throws IOException {throw new IOException("nested connection forbidden");}
            @Override public void close()throws IOException {
                if(!closed){
                    closed=true;closedData++;
                    if(store&&path!=null) {
                        files.put(path,bytes.toByteArray());
                        if(mutateSource!=null&&stores==1) {
                            java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(mutateSource);
                            byte[] source=Files.readAllBytes(mutateSource); source[44]^=1; Files.write(mutateSource,source); Files.setLastModifiedTime(mutateSource,time);
                        }
                        if(fault.equals("late-remote")&&path.endsWith("delivery.v1.txt")) {
                            String audio=path.substring(0,path.lastIndexOf('/')+1)+"audio.wav";
                            byte[] changed=files.get(audio).clone(); changed[44]^=1; files.put(audio,changed);
                        }
                    }
                }
                if(fault.equals("data-close"))throw new IOException("synthetic close failure");
            }
        }
    }
    private static String repeat(char c,int count){char[] v=new char[count];Arrays.fill(v,c);return new String(v);}
    private static String repeatLine(int width,int lines){StringBuilder b=new StringBuilder();for(int i=0;i<lines;i++)b.append(repeat('x',width)).append("\r\n");return b.toString();}
    private static FtpsTransfer.Result run(Fixture f,Fake t,String a,boolean reconcile,FtpsTransfer.Guard g)throws Exception {
        return FtpsTransfer.run(f.catalog,f.entry,PROFILE,PASSWORD,t,"profile-1",a,reconcile,g);
    }
    private static void happyRecovery()throws Exception {
        Fixture f=new Fixture(40000);Fake t=new Fake();String a=f.attempt();FtpsTransfer.Result r=run(f,t,a,false,OPEN);
        yes(r.verifiedAtTime);eq((long)f.original.length,r.bytes);eq(f.entry.metadata.wavSha256,r.wavSha256);
        eq(3,t.stores);eq(6,t.reads);yes(t.maxWrite<=32768);eq(9,t.securedData);eq(9,t.closedData);eq(1,t.closedControl);
        yes(Arrays.equals(f.original,t.files.get(a+"/audio.wav")));yes(!t.commands.contains("DELE"));f.retained();
        int before=t.stores;run(f,t,a,true,OPEN);eq(before,t.stores);f.retained();
        Fake collision=new Fake();collision.dirs.add(a);fails(()->run(f,collision,a,false,OPEN));eq(0,collision.stores);
        Fake missing=new Fake();fails(()->run(f,missing,a,true,OPEN));eq(0,missing.stores);yes(!missing.commands.contains("MKD"));
        Fake partial=new Fake();partial.dirs.add(a);partial.files.put(a+"/audio.wav",new byte[4]);fails(()->run(f,partial,a,true,OPEN));eq(0,partial.stores);f.retained();
    }
    private static void failures()throws Exception {
        for(String fault:new String[]{"connect","greeting","multiline","auth","control-tls","login","prot","epsv","pasv","data-tls","write","data-close","completion","lost-completion","same-size","short","extra","unreadable","zero-read","metadata","marker","late-remote"}) {
            Fixture f=new Fixture(2);Fake t=new Fake();t.fault=fault;fails(()->run(f,t,f.attempt(),false,OPEN));f.retained();
            if(fault.equals("auth")||fault.equals("control-tls")||fault.equals("greeting")||fault.equals("multiline"))yes(!t.commands.contains("USER")&&!t.commands.contains("PASS"));
            yes(!t.commands.contains("PASV")&&!t.commands.contains("PORT")&&!t.commands.contains("DELE"));
        }
    }
    private static void validation()throws Exception {
        fails(()->new FtpsTransfer.Profile("ftp://bad",21,"user","/recorder"));fails(()->new FtpsTransfer.Profile("example.invalid",0,"user","/recorder"));
        fails(()->new FtpsTransfer.Profile("example.invalid",21,"user\r\nPASS injected","/recorder"));fails(()->new FtpsTransfer.Profile("example.invalid",21,"user","/../other"));
        Fixture f=new Fixture(2);Fake t=new Fake();
        fails(()->FtpsTransfer.run(f.catalog,f.entry,PROFILE,new char[]{'x','\n'},t,"profile-1",f.attempt(),false,OPEN));eq(0,t.connections);
        fails(()->run(f,t,"../escape",false,OPEN));eq(0,t.connections);
        fails(()->run(f,t,FtpsTransfer.newAttemptName("other",f.entry.metadata.wavSha256,"profile-1"),false,OPEN));eq(0,t.connections);
        fails(()->run(f,t,f.attempt(),false,()->{throw new IOException("synthetic gate closed");}));eq(0,t.connections);
        for(int at:new int[]{15,30,50,80}) {Fake remote=new Fake();final int[] calls={0};fails(()->run(f,remote,f.attempt(),false,()->{if(++calls[0]==at)throw new IOException("synthetic revoke");}));f.retained();}
        byte[] bad=f.original.clone();bad[44]^=1;Files.write(f.wav,bad);fails(()->run(f,t,f.attempt(),false,OPEN));eq(0,t.connections);yes(Arrays.equals(bad,Files.readAllBytes(f.wav)));
        fails(()->f.catalog.load("../outside.ready"));fails(()->f.catalog.load(f.entry.id.replace(".ready",".part")));
        Fixture changed=new Fixture(2);Fake mutation=new Fake();mutation.mutateSource=changed.wav;
        fails(()->run(changed,mutation,changed.attempt(),false,OPEN));eq(1,mutation.stores);eq(0,mutation.reads);yes(Files.exists(changed.wav));
        Fixture valid=new Fixture(2);eq(valid.entry.id,valid.catalog.load(valid.entry.id).id);
    }
    public static void main(String[] args)throws Exception {happyRecovery();failures();validation();System.out.println("PASS FTPS: "+checks+" assertions; scripted transport only, no sockets/accounts/runtime acceptance");}
}
