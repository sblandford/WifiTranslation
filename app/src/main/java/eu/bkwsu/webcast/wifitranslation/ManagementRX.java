package eu.bkwsu.webcast.wifitranslation;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by simonb on 08/02/18.
 */

public class ManagementRX {
    private static final String TAG = "ManagementRX";
    private static Context context;

    private static String MULTICAST_MANAGEMENT_IP;
    private static int MULTICAST_MANAGEMENT_PORT;
    private static int MAX_MULTICAST_TIMEOUTS_BEFORE_KICK;
    private static int MAX_PACKET_SIZE;
    private static int MULTICAST_TIMEOUT;
    private static int PACKET_BUFFER_SIZE;
    //Time to wait for actions in seconds
    private static int ACTION_WAIT_TIMEOUT;

    private static boolean rxRun = false;
    private static boolean actionLock = false;

    //private static volatile JSONArray newChannelList;
    private static volatile boolean headphonesMandatory = false;
    public static volatile Map<Integer, AppState.Chan> channelMap = new ConcurrentHashMap();

    public enum Command {
        START, STOP, SLEEP
    }

    public ManagementRX (Properties prop) {
        context = MainActivity.context;

        ACTION_WAIT_TIMEOUT = Integer.parseInt(prop.getProperty("ACTION_WAIT_TIMEOUT"));
        MULTICAST_MANAGEMENT_IP = prop.getProperty("MULTICAST_MANAGEMENT_IP");
        MULTICAST_MANAGEMENT_PORT = Integer.parseInt(prop.getProperty("MULTICAST_MANAGEMENT_PORT"));
        MULTICAST_TIMEOUT = Integer.parseInt(prop.getProperty("RX_MULTICAST_TIMEOUT"));
        MAX_MULTICAST_TIMEOUTS_BEFORE_KICK = Integer.parseInt(prop.getProperty("RX_MAX_MULTICAST_TIMEOUTS_BEFORE_KICK"));
        MAX_PACKET_SIZE = Integer.parseInt(prop.getProperty("RX_MAX_PACKET_SIZE"));
        PACKET_BUFFER_SIZE = Integer.parseInt(prop.getProperty("RX_PACKET_BUFFER_SIZE"));

    }

    private static Thread rxThread = null;
    private static void newRxThread () {
        rxThread = new Thread() {
            @Override
            public void run() {
                byte[] packetBuff = new byte[PACKET_BUFFER_SIZE];
                int packetLength;
                MulticastSocket sock;
                Log.d(TAG, "Management RX Thread started");
                while (rxRun) {
                    if (Tools.isWifiOn()) {
                        try {
                            sock = new MulticastSocket(MULTICAST_MANAGEMENT_PORT);
                            sock.joinGroup(InetAddress.getByName(MULTICAST_MANAGEMENT_IP));
                            sock.setSoTimeout(MULTICAST_TIMEOUT);

                            //Main RX loop
                            for (int timeOutCount = 0; (timeOutCount < MAX_MULTICAST_TIMEOUTS_BEFORE_KICK) && rxRun; ) {
                                try {
                                    DatagramPacket pack = new DatagramPacket(packetBuff, PACKET_BUFFER_SIZE);
                                    Tools.acquireMulticastLock();
                                    sock.receive(pack);
                                    packetLength = pack.getLength();

                                    Log.d(TAG, "Packet received, length : " + packetLength);

                                    if (packetLength < MAX_PACKET_SIZE) {
                                        try {
                                            Boolean headphonesMandatory;
                                            Map<Integer, AppState.Chan> newChannelMap = new ConcurrentHashMap<>();

                                            JSONObject mgtObj = new JSONObject(packetBuff.toString());
                                            headphonesMandatory = mgtObj.getBoolean("headphonesMandatory");
                                            JSONArray newChannels = mgtObj.getJSONArray("channelList");

                                            newChannelMap.clear();

                                            for (int i = 0; i < newChannels.length(); i++) {
                                                AppState.Chan chan = new AppState.Chan();

                                                chan.name = newChannels.getJSONObject(i).getString("name");
                                                chan.viewId = -1;
                                                chan.busy = newChannels.getJSONObject(i).getBoolean("busy");
                                                chan.valid = newChannels.getJSONObject(i).getBoolean("valid");
                                                newChannelMap.put(i, chan);
                                            }
                                            updateManagement(headphonesMandatory, newChannelMap);
                                            Log.d(TAG, "Received valid management JSON");

                                        } catch (JSONException e) {
                                            Log.e(TAG, "Bad JSON received " + e.toString());
                                        }
                                    }
                                    timeOutCount = 0;
                                } catch (SocketTimeoutException e) {
                                    Log.d(TAG, "Timeout waiting for packet");
                                    timeOutCount++;
                                }
                            }
                        } catch (IOException e) {
                            Log.e(TAG,"IOException " + e.toString());
                        }
                    } else {
                        //Sit and wait for wifi to appear
                        Log.d(TAG, "RX waiting for wifi to reappear");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Wait for wifi sleep interrupted");

                        }
                    }
                }
            }
        };
    }

    private static synchronized void updateManagement(Boolean mandHP, Map<Integer, AppState.Chan> newChannelMap) {
        headphonesMandatory = mandHP;
        channelMap = newChannelMap;
    }

    private static void rxStart () {
        if (rxThread == null || (rxThread.getState() == Thread.State.TERMINATED)) {
            newRxThread ();
        }
        //Run if not already running
        if (!rxRun) {
            if (rxThread.getState() == Thread.State.NEW) {
                rxRun = true;
                rxThread.start();
            } else {
                Log.i(TAG, "Thread is not runnable and in state : " + rxThread.getState());
            }
        }
    }

    private static void rxStop () {
        if (rxRun && (rxThread != null)) {
            Log.d(TAG, "Stopping rx Thread");
            rxRun = false;
            try {
                rxThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Thread already dead while waiting");
            }
        } else {
            Log.d(TAG, "Thread already stopped so not stopping");
        }
    }

    private static synchronized void setActionLock (boolean state) {
        //Wait for previous lock release
        actionLock = state;
    }

    public static synchronized void fetchManagement(AppState state) {
        state.headphonesMandatory = headphonesMandatory;
        if (channelMap != null) {
            state.channelMap = new ConcurrentHashMap<>(channelMap);
        }
    }
    
    private static void actionWait () {
        for (int i = 0; (i < ACTION_WAIT_TIMEOUT) && actionLock; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Log.d(TAG, "Action lock wait interrupted");
            }
        }
    }

    public static void action (Command action) {
        actionWait();
        setActionLock(true);
        switch(action) {
            case START:
                rxStart();
                break;
            case STOP:
                rxStop();
                break;
        }
        setActionLock(false);
    }
}
