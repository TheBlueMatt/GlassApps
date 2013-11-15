package com.mattcorallo.glass.MusicController;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import com.mattcorallo.glass.MusicController.R;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class ControlActivity extends Activity {
    OutputStream os;
    boolean connected = false;
    Thread connectThread;

    private void sendMessage(int msg) {
        synchronized (ControlActivity.this) {
            if (connected) {
                try {
                    os.write(msg);
                } catch (IOException e) {
                    connected = false;
                    ControlActivity.this.notifyAll();
                }
            }
        }
    }

    private void connect() {
        if (connectThread != null)
            connectThread.interrupt();
        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                    BluetoothSocket s = null;
                    try {
                        Set<BluetoothDevice> ds = a.getBondedDevices();
                        BluetoothDevice d = ds.iterator().next();
                        s = d.createRfcommSocketToServiceRecord(UUID.fromString("11e19574-bde7-4b9c-845b-504d5b9dd160"));
                        s.connect();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                TextView v = (TextView) findViewById(R.id.infoText);
                                v.setText("Testing connection...");
                            }
                        });
                        os = s.getOutputStream();
                        os.write(42);
                        synchronized (ControlActivity.this) {
                            connected = true;
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                TextView v = (TextView) findViewById(R.id.infoText);
                                v.setText("Connected!");
                            }
                        });
                        synchronized (ControlActivity.this) {
                            ControlActivity.this.wait();
                        }
                    } catch (IOException e) {
                    } catch (InterruptedException e) {
                        Log.e("MUSIC", "Got Interrupted...");
                        try { s.close(); } catch (IOException e1) { /* don't care */ }
                        return;
                    }
                    synchronized (ControlActivity.this) {
                        connected = false;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView v = (TextView) findViewById(R.id.infoText);
                            v.setText("Lost connection, reconnecting...");
                        }
                    });
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Log.e("MUSIC", "Got Interrupted...");
                        if (s != null)
                            try { s.close(); } catch (IOException e1) { /* don't care */ }
                        return;
                    }
                }
            }
        });
        connectThread.setName("Bluetooth connect thread");
        connectThread.start();
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        ((Button)findViewById(R.id.backButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage(1);
            }
        });

        ((Button)findViewById(R.id.playButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage(2);
            }
        });

        ((Button)findViewById(R.id.nextButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage(3);
            }
        });

        ((Button)findViewById(R.id.volumeDownButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage(4);
            }
        });

        ((Button)findViewById(R.id.volumeUpButton)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage(5);
            }
        });

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MusicController");
    }

    PowerManager.WakeLock wl;
    @Override
    public void onResume() {
        super.onResume();
        wl.acquire();
        connect();
    }

    @Override
    public void onPause() {
        super.onPause();
        wl.release();
        if (connectThread != null)
            connectThread.interrupt();
    }
}
