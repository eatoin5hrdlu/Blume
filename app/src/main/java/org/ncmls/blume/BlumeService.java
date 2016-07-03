
package org.ncmls.blume;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import org.ncmls.blume.logger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Separate threads for incoming connections, connecing to device, communication.
 */
public class BlumeService {
    private static final String TAG = "Blume";

    private static final String NAME_SECURE = "BlumeSecure";
    private static final String NAME_INSECURE = "BlumeInsecure";

    private static final UUID MY_UUID_SECURE =UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID MY_UUID_INSECURE =UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;

    private int mState;

    public static final int  IDLE  = 0;
    public static final int  LISTENING = 1;
    public static final int  CONNECTING = 2;
    public static final int  CONNECTED = 3;

    public BlumeService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = IDLE;
        mHandler = handler;
    }

    public synchronized int getState()              { return (int) mState; }
    private synchronized void setState(int state) {
        Log.d(TAG, "setState " + mState + " -> " + state);
        mState = state;
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    public synchronized void start() {
        Log.d(TAG, "start");

        if (mConnectThread != null)   { mConnectThread.cancel();   mConnectThread = null;   }
        if (mConnectedThread != null) { mConnectedThread.cancel(); mConnectedThread = null; }

        setState(LISTENING);

        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        Log.d(TAG, "connect to " + device);

        if (mState == CONNECTING && mConnectThread != null ) {
                mConnectThread.cancel();
                mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
        setState(CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        Log.d(TAG, "connected, Socket Type:" + socketType);

        if (mConnectThread != null)       { mConnectThread.cancel(); mConnectThread = null;          }
        if (mConnectedThread != null)     { mConnectedThread.cancel(); mConnectedThread = null;      }
        if (mSecureAcceptThread != null)  { mSecureAcceptThread.cancel(); mSecureAcceptThread = null;}
        if (mInsecureAcceptThread != null){ mInsecureAcceptThread.cancel(); mInsecureAcceptThread = null;}

        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // Send device name back to the Activity
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        setState(CONNECTED);
    }

    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectThread != null)     {mConnectThread.cancel(); mConnectThread = null;      }
        if (mConnectedThread != null)    {mConnectedThread.cancel(); mConnectedThread = null;     }
        if (mSecureAcceptThread != null)  {mSecureAcceptThread.cancel(); mSecureAcceptThread = null; }
        if (mInsecureAcceptThread != null) {mInsecureAcceptThread.cancel(); mInsecureAcceptThread = null;}
        setState(IDLE);
    }

    /**
     * Unsynchronized write
     */
    public void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) { if (mState != CONNECTED) return; r = mConnectedThread; }
        r.write(out);
    }

    private void sendFailure(String msg) {
        Message m = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, msg);
        m.setData(bundle);
        mHandler.sendMessage(m);
        BlumeService.this.start();
    }

    private void connectionFailed() { sendFailure("Unable to connect device");   }
    private void connectionLost()   { sendFailure("Device connection was lost"); }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                            MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "listen() failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            Log.d(TAG, mSocketType + " mAcceptThread " + this);
            setName("AcceptThread" + mSocketType);
            BluetoothSocket socket = null;

            while (mState != CONNECTED) {
                try { socket = mmServerSocket.accept(); }
		catch (IOException e) { Log.e(TAG, "accept() failed", e); break; }

                if (socket != null) {
                    synchronized (BlumeService.this) {
                        switch (mState) {
                            case LISTENING:
                            case CONNECTING:
                                connected(socket, socket.getRemoteDevice(), mSocketType);
                                break;
                            case IDLE:
                            case CONNECTED:
                                try { socket.close(); }
				catch (IOException e) {Log.e(TAG, "failed to close socket", e); }
                                break;
                        }
                    }
                }
            }
            Log.i(TAG, "EXIT mAcceptThread " + mSocketType);
        }

        public void cancel() {
            Log.d(TAG, "cancel " + mSocketType);
            try { mmServerSocket.close(); }
	    catch (IOException e) { Log.e(TAG, "failed close " + mSocketType, e); }
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
                            MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
                            MY_UUID_INSECURE);
                }
            } catch (IOException e) { Log.e(TAG, mSocketType + " create() failed", e); }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "mConnectThread: " + mSocketType);
            setName("ConnectThread" + mSocketType);
            // mAdapter.cancelDiscovery(); // Cancel discovery to speed up connection?

            try { mmSocket.connect(); }
	    catch (IOException e) {
                try { mmSocket.close(); }
		catch (IOException e2) { Log.e(TAG, "Failed close()", e2); }
                connectionFailed();
                return;
            }
            synchronized (BlumeService.this) { mConnectThread = null; }
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try                   { mmSocket.close();}
	    catch (IOException e) { Log.e(TAG, "Failed to close socket", e);}
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private byte[]  reply = new byte[100];
        private int rinx = 0;
        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tIn = null;
            OutputStream tOut = null;

            try   { tIn = socket.getInputStream(); tOut = socket.getOutputStream(); }
	    catch (IOException e) { Log.e(TAG, "Filed to create I/O sockets", e);   }
            mmInStream = tIn;
	    mmOutStream = tOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[2048];
            int bytes;

            while (true) {
                try {
                    try { sleep(100); } catch (InterruptedException e) { e.printStackTrace(); }
                    bytes = mmInStream.read(buffer);
                    int i = 0;
                    if (bytes > 2047) { Log.i(TAG,"More than 2K bytes"); bytes = 1024; }
                    for(i=0; i<bytes; i++) { reply[rinx++] = buffer[i]; }
                    Log.i(TAG,new String(reply));
                    if (reply[rinx-1] == 10) {
                        mHandler.obtainMessage(Constants.MESSAGE_READ, rinx, -1, reply).sendToTarget();
                        reply[0] = 0;
                        rinx = 0;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    BlumeService.this.start();
                    break;
                }
            }
        }

        public void write(byte[] buffer) {
            try { mmOutStream.write(buffer);
                  mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer).sendToTarget();
            } catch (IOException e) { Log.e(TAG, "Exception during write", e); }
        }

        public void cancel() {
            try                   { mmSocket.close(); }
	    catch (IOException e) { Log.e(TAG, "cancel: close() socket failed", e); }
        }
    }
}
