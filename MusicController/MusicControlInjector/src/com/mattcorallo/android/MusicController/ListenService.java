package com.mattcorallo.android.MusicController;

import android.app.KeyguardManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Listens for bluetooth connections and does stuff
 */
public class ListenService extends Service {
    public IBinder onBind(Intent intent) {
        return null;
    }

    Thread listenThread;
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (listenThread != null)
            listenThread.interrupt();

        listenThread = new Thread(new Runnable() {
            @Override
            public void run() {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                BluetoothServerSocket ss;
                try {
                    ss = a.listenUsingRfcommWithServiceRecord("MusicControl", UUID.fromString("11e19574-bde7-4b9c-845b-504d5b9dd160"));
                } catch (IOException e) {
                    Log.e("Music", "Couldn't listen for bluetooth connections");
                    return;
                }

                while (true) {
                    BluetoothSocket s = null;
                    try {
                        s = ss.accept();
                        InputStream is = s.getInputStream();
                        int c;
                        while ((c = is.read()) != -1) {
                            boolean unlockLock = !powerManager.isScreenOn();
                            Log.e("Music", "Lock: " + unlockLock);
                            if (unlockLock) {
                                Runtime.getRuntime().exec(new String[] {"su", "-c", "input keyevent " + KeyEvent.KEYCODE_POWER});
                                try { Thread.sleep(100); } catch (InterruptedException e) {}
                            }

                            if (c == 1)
                                Runtime.getRuntime().exec(new String[] {"su", "-c", "input keyevent " + KeyEvent.KEYCODE_MEDIA_PREVIOUS});
                            else if (c == 2)
                                Runtime.getRuntime().exec(new String[] {"su", "-c", "input keyevent " + KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE});
                            else if (c == 3)
                                Runtime.getRuntime().exec(new String[] {"su", "-c", "input keyevent " + KeyEvent.KEYCODE_MEDIA_NEXT});
                            else if (c == 4)
                                Runtime.getRuntime().exec(new String[] {"su", "-c", "input keyevent " + KeyEvent.KEYCODE_VOLUME_DOWN});
                            else if (c == 5)
                                Runtime.getRuntime().exec(new String[] {"su", "-c", "input keyevent " + KeyEvent.KEYCODE_VOLUME_UP});
                        }
                        s.close();
                    } catch (IOException e) {
                        try {
                            if (s != null)
                                s.close();
                        } catch (IOException e1) {
                            Log.e("Music", "Failed to close socket", e1);
                        }
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Log.e("Music", "Interrupted");
                        break;
                    }
                }
                try {
                    ss.close();
                } catch (IOException e) {
                    Log.e("Music", "Failed to close server socket");
                }
            }
        });
        listenThread.start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (listenThread != null)
            listenThread.interrupt();
    }
}
