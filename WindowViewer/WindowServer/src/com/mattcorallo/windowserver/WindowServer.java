package com.mattcorallo.windowserver;

import javax.net.ssl.*;
import javax.security.cert.CertificateEncodingException;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Do stuff
 */
public class WindowServer {
    final Runtime rt = Runtime.getRuntime();

    /*Set<String> nonWindows = new HashSet<String>();
    public List<String> getWindowList() throws IOException {
        Process p = rt.exec(new String[]{"/bin/sh", "-c", "xwininfo -tree -root | grep -v 'easystroke' | awk '{ print $1 }' | grep '^0x'"});
        InputStream stream = p.getInputStream();

        Scanner in = new Scanner(stream);
        List<String> res = new ArrayList<String>();
        try {
            while (true) {
                String l = in.nextLine();
                if (!nonWindows.contains(l))
                    res.add(l);
            }
        } catch (NoSuchElementException e) {}

        Collections.sort(res);
        return res;
    }*/
    Set<String> windows = Collections.synchronizedSet(new HashSet<String>());
    public List<String> getWindowList() throws IOException {
        List<String> res = new ArrayList<String>();
        synchronized (windows) {
            for (String s : windows)
                res.add(s);
        }
        Collections.sort(res);
        return res;
    }

    public static void main(String[] args) throws Exception {
        new WindowServer().run();
    }

    AtomicInteger change = new AtomicInteger(0);
    AtomicReference<String> setWindow = new AtomicReference<String>();
    volatile int remoteCounter = 42;
    public void dumpWindow(OutputStream out) throws IOException {
        String currentId = "";
        for (int counter = 0; true; counter++) {
            long startTime = System.currentTimeMillis();

            List<String> windows = getWindowList();
            if (windows.isEmpty()) {
                counter--;
                continue;
            }

            String newWindow = setWindow.getAndSet(null);
            if (newWindow != null)
                System.out.println("Looking for " + newWindow);
            int windowId = 0;
            for (int i = 0; i < windows.size(); i++) {
                if (windows.get(i).equals(currentId))
                    windowId = i;
                if (windows.get(i).equals(newWindow)) {
                    windowId = i;
                    change.getAndSet(0);
                    System.out.println("Found " + newWindow);
                }
            }
            windowId += change.getAndSet(0);
            while (windowId < 0)
                windowId += windows.size();
            windowId = windowId % windows.size();
            currentId = windows.get(windowId);

            synchronized (WindowServer.this) {
                if ((0xff & remoteCounter) < (0xff & counter) - 2)
                    try {
                        WindowServer.this.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
            }

            try {
                rt.exec(new String[] {"/bin/sh", "-c", "xwd -id " + currentId + " > w.xwd"}).waitFor();
                rt.exec("convert w.xwd PNG:w.png").waitFor();
            } catch (InterruptedException e) {
                return;
            }

            long fileLength = new File("w.png").length();
            if (fileLength == 0) {
                //System.err.println("Marking " + currentId + " as invalid (" + windows.size() + " windows)");
                //nonWindows.add(currentId);
                counter--;
                continue;
            }
            //System.err.println("Sending " + currentId + " of size " + fileLength);
            out.write(counter);
            for (int i = 0; i < 8; i++)
                out.write((int) (0xFF & (fileLength >>> i*8)));

            InputStream imgIn = new FileInputStream("w.png");
            byte[] buff = new byte[8192];
            int len;
            while ((len = imgIn.read(buff)) != -1)
                out.write(buff, 0, len);

            new File("w.png").delete();
            new File("w.xwd").delete();

            try {
                long sleepFor = 100 - (System.currentTimeMillis() - startTime);
                if (sleepFor > 0)
                    Thread.sleep(sleepFor);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    volatile String chromeWindow = "0x42";
    public void readInput() {
        Scanner in = new Scanner(System.in);
        while (true) {
            String line = in.nextLine();
            if (line.startsWith("chrome "))
                chromeWindow = line.substring(7);
            else if (line.startsWith("+"))
                windows.add(line.substring(1));
            else if (line.startsWith("-"))
                windows.remove(line.substring(1));
            else
                setWindow.set(line);
        }
    }

    public void postAcceptConnection(SSLSocket socket) {
        try {
            final OutputStream out = socket.getOutputStream();
            final InputStream in = socket.getInputStream();

            Thread dumpThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        dumpWindow(out);
                    } catch (IOException e) { /* Next connection, pls */ }
                    closeConnection = true;
                }
            });
            dumpThread.start();

            while (true) {
                int type = in.read();
                int value = in.read();
                if (type == -1 || value == -1)
                    break;

                if (type == 1) {
                    synchronized (WindowServer.this) {
                        remoteCounter = value;
                        WindowServer.this.notifyAll();
                    }
                } else if (type == 2) {
                    System.err.println("Got command " + value);
                    if (value > 0 && value < 6) {
                        Runtime.getRuntime().exec(PATH_TO_COMPILED_xkeyin.c + " -w " + chromeWindow + " -t " + value).waitFor();
                        Runtime.getRuntime().exec("xdotool key --clearmodifiers ctrl+super+j").waitFor();
                        Runtime.getRuntime().exec("xdotool key --clearmodifiers ctrl+super+j").waitFor();
                    } else if (value == 6)
                        change.decrementAndGet();
                    else if (value == 7)
                        change.incrementAndGet();
                }
            }

            System.out.println("Connection closed...");
            dumpThread.interrupt();
        } catch (Exception e) { /* Get next connection */ }
    }

    volatile boolean closeConnection = false;
    public void run() throws IOException, InterruptedException {
        SSLContext ctx;
        try {
            KeyStore ks;
            ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(PATH_TO_YOUR_KEYSTORE), "qwerty".toCharArray());

            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(ks, "qwerty".toCharArray());

            ctx = SSLContext.getInstance("TLS");
            ctx.init(factory.getKeyManagers(), new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            byte[] hash = BYTE_ARRAY_WITH_SHA256_HASH_OF_THE_CERT_ON_YOUR_GLASS (run once and let this fail to get the byte array, probably);
                            try {
                                if (chain.length != 1 ||
                                    !Arrays.equals(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()), hash))
                                    throw new CertificateException();
                            } catch (NoSuchAlgorithmException e) {
                                throw new RuntimeException(e);
                            }
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            }, new SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SSLServerSocket serverSocket = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
        serverSocket.setWantClientAuth(true);
        serverSocket.bind(new InetSocketAddress(PORT_TO_LISTEN_ON));

        Thread inThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readInput();
            }
        });
        inThread.start();

        while (true) {
            final SSLSocket socket = (SSLSocket) serverSocket.accept();
            /*socket.addHandshakeCompletedListener(new HandshakeCompletedListener() {
                @Override
                public void handshakeCompleted(HandshakeCompletedEvent event) {
                    synchronized (WindowServer.this) {
                        WindowServer.this.notifyAll();
                    }
                }
            });*/

            synchronized (WindowServer.this) {
                socket.startHandshake();
                //WindowServer.this.wait();
            }

            System.out.println("Accepted socket from " + socket.getInetAddress());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    postAcceptConnection(socket);
                }
            }).start();
        }
    }
}
