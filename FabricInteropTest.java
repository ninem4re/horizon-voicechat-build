// Independent SVC 2.6.x (compatibility 20) client, written from the Java protocol layouts.
// Reference: henkelmax/simple-voice-chat commit 2e56198016e02fd99139d37f382b7155c5bd63f5.
// Uses JDK AES-GCM and UDP, never the Rust relay's encoders or encryption helpers.
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class FabricInteropTest {
    static final HexFormat HEX = HexFormat.of();
    static final SecureRandom RNG = new SecureRandom();
    static final byte[] OPUS = {(byte)0xF8, (byte)0xFF, (byte)0xFE}; // valid Opus silence frame
    interface Write { void put(DataOutputStream out) throws Exception; }
    static byte[] encode(Write write) throws Exception {
        var bytes = new ByteArrayOutputStream();
        write.put(new DataOutputStream(bytes)); return bytes.toByteArray();
    }
    static DataInputStream read(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void uuid(DataOutputStream out, UUID id) throws Exception { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    static UUID uuid(DataInputStream in) throws Exception { return new UUID(in.readLong(), in.readLong()); }
    static void varint(DataOutputStream out, int value) throws Exception {
        do { int b = value & 127; value >>>= 7; out.writeByte(b | (value == 0 ? 0 : 128)); } while (value != 0);
    }
    static int varint(DataInputStream in) throws Exception {
        int value = 0; for (int i=0; i<5; i++) { int b=in.readUnsignedByte(); value |= (b&127) << (7*i); if ((b&128)==0) return value; }
        throw new IOException("bad VarInt");
    }
    static void array(DataOutputStream out, byte[] value) throws Exception { varint(out,value.length); out.write(value); }
    static byte[] array(DataInputStream in) throws Exception {
        int n=varint(in); check(n>=0 && n<=4096,"bounded byte array"); byte[] b=in.readNBytes(n); check(b.length==n,"complete byte array"); return b;
    }
    static void text(DataOutputStream out, String value) throws Exception { array(out,value.getBytes(StandardCharsets.UTF_8)); }
    static String text(DataInputStream in) throws Exception { return new String(array(in),StandardCharsets.UTF_8); }
    record Control(long player, String channel, byte[] payload, String transport) {}
    static class Probe implements AutoCloseable {
        final Process process;
        final BufferedReader input;
        final PrintWriter output;
        Probe(String path) throws Exception {
            process = new ProcessBuilder(path).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            input = new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            output = new PrintWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8),true);
            String registration=input.readLine();
            check(registration!=null && registration.startsWith("REGISTER\t"),"registration available");
            String channels=new String(HEX.parseHex(registration.split("\t")[1]),StandardCharsets.UTF_8);
            check(Set.of(channels.split("\0")).equals(Set.of("voicechat:request_secret","voicechat:update_state","voicechat:create_group","voicechat:set_group","voicechat:leave_group")),"Fabric server channel advertisement");
        }
        List<Control> command(String line) throws Exception {
            output.println(line); var result=new ArrayList<Control>();
            while (true) {
                String response=input.readLine(); check(response!=null,"relay probe is alive");
                if(response.equals("END")) return result;
                String[] fields=response.split("\t",-1); check(fields.length==5 && fields[0].equals("OUT"),"probe response format");
                Control c=new Control(Long.parseLong(fields[1]),fields[2],HEX.parseHex(fields[3]),fields[4]);
                check(c.player()!=6,"unmodded player receives no voice-control packets");
                check(c.transport().equals(c.player()==5 ? "horizon" : "fabric"),"correct transport for recipient");
                result.add(c);
            }
        }
        List<Control> send(long id,String channel,byte[] payload) throws Exception {
            return command("MSG\t"+id+"\t"+channel+"\t"+(payload.length==0?"-":HEX.formatHex(payload))+"\t"+(id==5?"horizon":"fabric"));
        }
        public void close() { output.println("QUIT"); process.destroy(); }
    }
    static class Client implements AutoCloseable {
        final UUID id;
        final byte[] secret;
        final DatagramSocket socket;
        final InetSocketAddress server;
        Client(Probe probe,long number) throws Exception {
            id=new UUID(0,number);
            var messages=probe.send(number,"voicechat:request_secret",encode(o->o.writeInt(20)));
            var secretMessage=messages.stream().filter(c->c.player()==number && c.channel().equals("voicechat:secret")).findFirst().orElseThrow();
            var in=read(secretMessage.payload()); secret=in.readNBytes(16);
            int port=in.readInt(); check(uuid(in).equals(id),"SVC secret player UUID");
            check(in.readUnsignedByte()==0,"VoIP codec"); check(in.readInt()==1024,"MTU");
            check(in.readDouble()==48.0,"voice distance"); check(in.readInt()==1000,"keep-alive");
            check(in.readBoolean(),"groups enabled"); check(text(in).isEmpty(),"default voice host");
            check(!in.readBoolean() && in.available()==0,"recording flag and exact SVC secret layout");
            socket=new DatagramSocket(new InetSocketAddress("127.0.0.1",0)); socket.setSoTimeout(100);
            server=new InetSocketAddress("127.0.0.1",port);
            send(encode(o->{o.writeByte(5);uuid(o,id);o.write(secret);}));
            check(receive(6,1200)!=null,"Java AES-GCM authentication accepted");
            send(new byte[]{9}); check(receive(10,1200)!=null,"UDP connection check accepted");
            probe.command("TICK");
        }
        byte[] send(byte[] plain) throws Exception {
            byte[] nonce=new byte[12]; RNG.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,nonce));
            byte[] sealed=encode(o->{o.write(nonce);o.write(cipher.doFinal(plain));});
            byte[] datagram=encode(o->{o.writeByte(255);uuid(o,id);array(o,sealed);});
            socket.send(new DatagramPacket(datagram,datagram.length,server)); return datagram;
        }
        byte[] talk(long sequence,boolean whisper) throws Exception {
            return send(encode(o->{o.writeByte(1);array(o,OPUS);o.writeLong(sequence);o.writeBoolean(whisper);}));
        }
        byte[] receive(int wanted,int timeout) throws Exception {
            long end=System.nanoTime()+timeout*1_000_000L;
            while(System.nanoTime()<end) {
                var packet=new DatagramPacket(new byte[4096],4096);
                try {socket.receive(packet);} catch(SocketTimeoutException e) {continue;}
                check(packet.getSocketAddress().equals(server),"datagram comes from configured server");
                var in=read(Arrays.copyOf(packet.getData(),packet.getLength())); check(in.readUnsignedByte()==255,"SVC magic byte");
                byte[] sealed=array(in); check(in.available()==0 && sealed.length>=28,"encrypted datagram bounds");
                var cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,Arrays.copyOf(sealed,12)));
                byte[] plain=cipher.doFinal(Arrays.copyOfRange(sealed,12,sealed.length));
                if(plain[0]==8) {send(new byte[]{8}); continue;}
                if(Byte.toUnsignedInt(plain[0])==wanted) return plain;
            }
            return null;
        }
        public void close() {socket.close();}
    }
    static void verifyVoice(byte[] plain,int type,long sender,long sequence) throws Exception {
        check(plain!=null,"voice packet received"); var in=read(plain);
        check(in.readUnsignedByte()==type,"voice packet type");
        check(uuid(in).equals(new UUID(0,sender)) && uuid(in).equals(new UUID(0,sender)),"voice channel and sender UUIDs");
        check(Arrays.equals(array(in),OPUS),"Opus bytes preserved"); check(in.readLong()==sequence,"sequence preserved");
        if(type==2) check(in.readFloat()==48.0f,"positional distance");
        check(in.readUnsignedByte()==0 && in.available()==0,"voice flags and exact SVC packet layout");
    }
    public static void main(String[] args) throws Exception {
        try(var probe=new Probe(args[0])) {
            check(probe.send(1,"voicechat:request_secret",new byte[]{0,0,0,21}).isEmpty(),"unsupported protocol rejected");
            try(var a=new Client(probe,1);var b=new Client(probe,2);var far=new Client(probe,3);var nether=new Client(probe,4);var horizon=new Client(probe,5)) {
                a.talk(1,false);
                verifyVoice(b.receive(2,1200),2,1,1);
                verifyVoice(horizon.receive(2,1200),2,1,1);
                check(far.receive(2,350)==null,"out-of-range player hears no proximity voice");
                check(nether.receive(2,350)==null,"other dimension hears no proximity voice");
                probe.command("MOVE\t2\t500\tminecraft:the_nether");
                var groupPackets=probe.send(1,"voicechat:create_group",encode(o->{text(o,"Fabric test");o.writeBoolean(true);text(o,"password");o.writeShort(0);}));
                var joined=groupPackets.stream().filter(c->c.player()==1 && c.channel().equals("voicechat:joined_group")).findFirst().orElseThrow();
                var in=read(joined.payload()); check(in.readBoolean(),"group creation succeeded"); UUID group=uuid(in); check(!in.readBoolean() && in.available()==0,"joined-group layout");
                var wrong=probe.send(2,"voicechat:set_group",encode(o->{uuid(o,group);o.writeBoolean(true);text(o,"wrong");}));
                var rejected=read(wrong.stream().filter(c->c.player()==2 && c.channel().equals("voicechat:joined_group")).findFirst().orElseThrow().payload());
                check(!rejected.readBoolean() && rejected.readBoolean(),"wrong group password rejected");
                var joinedPackets=probe.send(2,"voicechat:set_group",encode(o->{uuid(o,group);o.writeBoolean(true);text(o,"password");}));
                var accepted=read(joinedPackets.stream().filter(c->c.player()==2 && c.channel().equals("voicechat:joined_group")).findFirst().orElseThrow().payload());
                check(accepted.readBoolean() && uuid(accepted).equals(group) && !accepted.readBoolean(),"password-protected group join");
                byte[] replay=a.talk(2,false); verifyVoice(b.receive(3,1200),3,1,2);
                a.socket.send(new DatagramPacket(replay,replay.length,a.server)); check(b.receive(3,350)==null,"replayed voice rejected");
                probe.send(2,"voicechat:update_state",new byte[]{1}); a.talk(3,false); check(b.receive(3,350)==null,"disabled client receives no group voice");
                probe.send(2,"voicechat:update_state",new byte[]{0});
                probe.send(2,"voicechat:leave_group",new byte[0]);
                a.talk(4,false); check(b.receive(3,350)==null,"left group receives no group voice");
                probe.command("LEFT\t2");
            }
        }
        System.out.println("PASS: Fabric channel registration, protocol-20 secret, independent Java AES-GCM/UDP authentication, proximity, dimensions, mixed Horizon/Fabric clients, password groups, disabled state and replay protection.");
    }
}
