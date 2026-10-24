# The provider builds service class names from its own package and JCA invokes
# their public no-argument constructors reflectively. Keep all three names,
# including the provider's package anchor, even when only key decoding is used.
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519Provider {
    public <init>();
}
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519KeyFactory {
    public <init>();
}
-keep class org.connectbot.sshlib.crypto.ed25519.Ed25519KeyPairGenerator {
    public <init>();
}
