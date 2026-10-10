package android.net; public final class Network {
 private final int id; public Network(int id) {this.id=id;} public boolean equals(Object o){return o instanceof Network&&((Network)o).id==id;} public int hashCode(){return id;}
 public javax.net.SocketFactory getSocketFactory(){throw new AssertionError("No socket operation permitted in admission test");}
}
