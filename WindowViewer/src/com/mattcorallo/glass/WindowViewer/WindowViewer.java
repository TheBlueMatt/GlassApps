package com.mattcorallo.glass.WindowViewer;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class WindowViewer extends Activity {
    Thread connectionThread;
    volatile boolean running = false;

    List<Byte> commandQueue = Collections.synchronizedList(new LinkedList<Byte>());
    OutputStream out;
    private void connect() {
        if (connectionThread != null)
            connectionThread.interrupt();

        connectionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final ImageView v = (ImageView) findViewById(R.id.imageView);

                SSLContext ctx = null;
                try {
                    KeyStore ks = null;
                    ks = KeyStore.getInstance("BKS");
                    // keytool -genkey -alias WindowViewer -keystore ./res/raw/keystore.bks -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath ./bcprov-jdk15on-146.jar -storetype BKS
                    ks.load(getApplicationContext().getResources().openRawResource(R.raw.keystore), "qwerty".toCharArray());

                    KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    factory.init(ks, "qwerty".toCharArray());

                    ctx = SSLContext.getInstance("TLS");
                    ctx.init(factory.getKeyManagers(), new TrustManager[] {
                            new X509TrustManager() {
                                @Override
                                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                                @Override
                                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                                @Override
                                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            }
                    }, new SecureRandom()); // We dont care about verifying the server
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }


                while (true) {
                    SSLSocket s = null;
                    try {
                        s = (SSLSocket) ctx.getSocketFactory().createSocket(YOUR_SERVER_HERE);
                        s.setWantClientAuth(true);
                        s.addHandshakeCompletedListener(new HandshakeCompletedListener() {
                            @Override
                            public void handshakeCompleted(HandshakeCompletedEvent event) {
                                synchronized (WindowViewer.this) {
                                    WindowViewer.this.notifyAll();
                                }
                            }
                        });
                        synchronized (WindowViewer.this) {
                            s.startHandshake();
                            //WindowViewer.this.wait();
                        }

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ((TextView) findViewById(R.id.statusText)).setText("");
                            }
                        });

                        out = s.getOutputStream();
                        InputStream in = s.getInputStream();

                        while (true) {
                            if (!running)
                                return;

                            byte[] lenAndCounter = new byte[9];
                            int o = 0;
                            while (o < 9) {
                                int r = in.read(lenAndCounter, o, 9-o);
                                if (r == -1) {
                                    s.close();
                                    break;
                                }
                                o += r;
                            }
                            long len = ((((long)0xff & lenAndCounter[1])) << 8*0) |
                                       (((long)(0xff & lenAndCounter[2])) << 8*1) |
                                       (((long)(0xff & lenAndCounter[3])) << 8*2) |
                                       (((long)(0xff & lenAndCounter[4])) << 8*3) |
                                       (((long)(0xff & lenAndCounter[5])) << 8*4) |
                                       (((long)(0xff & lenAndCounter[6])) << 8*5) |
                                       (((long)(0xff & lenAndCounter[7])) << 8*6) |
                                       (((long)(0xff & lenAndCounter[8])) << 8*7);

                            Log.d("Viewer", "Decompressing image of size " + len);
                            if (len == 0)
                                continue;

                            byte[] img = new byte[(int) len];
                            o = 0;
                            while (o != len) {
                                int r = in.read(img, o, (int) (len - o));
                                if (r == -1) {
                                    s.close();
                                    break;
                                }
                                o += r;
                            }

                            final Bitmap image = BitmapFactory.decodeByteArray(img, 0, img.length);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    v.setImageBitmap(image);
                                }
                            });

                            synchronized (out) {
                                out.write(new byte[] {1, lenAndCounter[0]});
                            }
                        }
                    } catch (IOException e) {
                        Log.e("Viewer", "IOException", e);
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e1) {
                            return;
                        }
                    //} catch (InterruptedException e) {
                    //    return;
                    } finally {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ((TextView)findViewById(R.id.statusText)).setText(" D");
                            }
                        });

                        try {
                            if (s != null)
                                s.close();
                        } catch (IOException e) { /* */ }
                    }
                }
            }
        });
        connectionThread.start();
    }

    Executor sendMessageExecutor = Executors.newSingleThreadExecutor();
    private void sendMessage(final byte b) {
        sendMessageExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (out) {
                        out.write(new byte[] {2, b});
                    }
                } catch (Exception e) { /* Not connected (IOException, NullPointerException, etc */ }
            }
        });
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ((Button) findViewById(R.id.prevButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 1);
            }
        });

        ((Button) findViewById(R.id.playPauseButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 5);
            }
        });

        ((Button) findViewById(R.id.nextButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 2);
            }
        });

        ((Button) findViewById(R.id.volumeDownButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 3);
            }
        });

        ((Button) findViewById(R.id.volumeUpButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 4);
            }
        });

        ((Button) findViewById(R.id.previousWindowButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 6);
            }
        });

        ((Button) findViewById(R.id.nextWindowButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage((byte) 7);
            }
        });

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "WindowViewer");
    }

    PowerManager.WakeLock wl;
    @Override
    public void onResume() {
        super.onResume();

        wl.acquire();
        running = true;
        connect();
    }

    @Override
    public void onPause() {
        super.onPause();

        wl.release();
        if (connectionThread != null)
            connectionThread.interrupt();
        running = false;
    }
}
