package com.trilead.ssh2.channel;

import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.packets.TypesReader;
import com.trilead.ssh2.packets.TypesWriter;
import com.trilead.ssh2.transport.ITransportConnection;
import com.trilead.ssh2.transport.MessageHandler;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises wire responses without a network, keys, or timing-dependent server. */
public class ChannelCancellationTest {
    @Test public void interruptedOpenClosesLateConfirmationOnlyOnce() throws Exception {
        FakeTransport transport = new FakeTransport();
        ChannelManager manager = new ChannelManager(transport);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { manager.openSessionChannel(); } catch (Throwable error) { failure.set(error); }
        });
        caller.start();
        assertTrue(transport.sent.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2_000);
        assertFalse(caller.isAlive());
        assertTrue(failure.get() instanceof java.io.InterruptedIOException);
        TypesReader request = new TypesReader(transport.request);
        request.readByte();
        request.readString();
        int local = request.readUINT32();
        TypesWriter reply = new TypesWriter();
        reply.writeByte(91);
        reply.writeUINT32(local);
        reply.writeUINT32(7);
        reply.writeUINT32(65536);
        reply.writeUINT32(32768);
        manager.handleMessage(reply.getBytes(), reply.length());
        assertEquals(1, transport.closes);
    }

    @Test public void absentPingResponseHasDeadline() {
        FakeTransport transport = new FakeTransport();
        ChannelManager manager = new ChannelManager(transport);
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> manager.requestGlobalTrileadPing(20));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_000);
    }

    private static class FakeTransport implements ITransportConnection {
        final CountDownLatch sent = new CountDownLatch(1);
        volatile byte[] request;
        int closes;
        public void registerMessageHandler(MessageHandler handler, int low, int high) { }
        public void sendMessage(byte[] message) { request = message.clone(); sent.countDown(); }
        public void sendAsynchronousMessage(byte[] message) { if (message[0] == 97) closes++; }
        public int getPacketOverheadEstimate() { return 32; }
        public ConnectionInfo getConnectionInfo(int number) { return new ConnectionInfo(); }
        public byte[] getSessionIdentifier() { return new byte[0]; }
        public String getHostname() { return "synthetic.invalid"; }
        public int getPort() { return 22; }
        public ServerHostKeyVerifier getServerHostKeyVerifier() { return null; }
    }
}
