package eu.bkwsu.webcast.wifitranslation;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Properties;

public class HubComms {
    private static final String TAG = "HubComms";

    private static boolean hubIpRun = false;
    private static boolean hubPollRun = false;
    private static String hubIp = "";

    private static int HUB_BROADCAST_LENGTH_MAX;
    private static int HUB_BROADCAST_LENGTH_MIN;
    private static int HUB_BROADCAST_PORT;
    private static String HUB_HOSTNAME;
    private static int HUB_POLL_ATTEMPTS;
    private static String HUB_STAT_PATH;
    private static int HUB_STAT_PORT;
    private static int PACKET_BUFFER_SIZE;

    public HubComms (Properties prop) {
        HUB_BROADCAST_LENGTH_MAX = Integer.parseInt(prop.getProperty("HUB_BROADCAST_LENGTH_MAX"));
        HUB_BROADCAST_LENGTH_MIN = Integer.parseInt(prop.getProperty("HUB_BROADCAST_LENGTH_MIN"));
        HUB_BROADCAST_PORT = Integer.parseInt(prop.getProperty("HUB_BROADCAST_PORT"));
        HUB_HOSTNAME = prop.getProperty("HUB_HOSTNAME");
        HUB_POLL_ATTEMPTS = Integer.parseInt(prop.getProperty("HUB_POLL_ATTEMPTS"));
        HUB_STAT_PATH = prop.getProperty("HUB_STAT_PATH");
        HUB_STAT_PORT = Integer.parseInt(prop.getProperty("HUB_STAT_PORT"));
        PACKET_BUFFER_SIZE = Integer.parseInt(prop.getProperty("RX_PACKET_BUFFER_SIZE"));
    }


    //The Hub will send a broadcast packet every few seconds to signal its IP address
    //If there is no local DNS set up then the Hub IP address can be possibly obtained this way
    private static Thread broadcastRxThread = null;
    private static void mainBroadcastRxThread () {
        broadcastRxThread = new Thread() {
            @Override
            public void run() {
                DatagramSocket sock;
                int packetLength;
                try {
                    byte[] packetBuff = new byte[PACKET_BUFFER_SIZE];

                    try {
                        InetAddress address = InetAddress.getByName(HUB_HOSTNAME);
                        hubIp = address.getHostAddress();
                        Log.i(TAG, "Hub IP address detected by name resolution : " + hubIp);
                        hubIpRun = false;
                        pollHubStart ();
                    } catch (UnknownHostException e) {
                        Log.i(TAG, "Unable to resolve Hub by name : " + HUB_HOSTNAME);
                    }

                    //Keep a socket open to listen to all the UDP trafic that is destined for this port
                    sock = new DatagramSocket(HUB_BROADCAST_PORT, InetAddress.getByName("0.0.0.0"));
                    sock.setBroadcast(true);

                    while(hubIpRun) {
                        DatagramPacket pack = new DatagramPacket(packetBuff, PACKET_BUFFER_SIZE);
                        sock.receive(pack);
                        packetLength = pack.getLength();

                        if ((packetLength >= HUB_BROADCAST_LENGTH_MIN ) && (packetLength <= HUB_BROADCAST_LENGTH_MAX )){
                            Log.d(TAG, "Broadcast packet received with length : " + packetLength);

                            String hubMsg[] = new String(packetBuff, 0, packetLength, Charset.forName("US-ASCII")).split(" ");
                            if (hubMsg.length == 2) {
                                hubIp = hubMsg[1];
                                Log.i(TAG, "Hub IP address detected from broadcast : " + hubIp);
                                //Mission accomplished so stop
                                hubIpRun = false;
                                pollHubStart ();
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("IOException " + e.toString());
                }
            }
        };
    }
    static void broadcastStart () {
        if (broadcastRxThread == null || (broadcastRxThread.getState() == Thread.State.TERMINATED)) {
            mainBroadcastRxThread();
        }
        if (broadcastRxThread.getState() == Thread.State.NEW) {
            Log.d(TAG, "Starting Broadcast RX Thread");
            hubIpRun = true;
            broadcastRxThread.start();
        }
    }
    static void broadcastStop () {
        if (hubIpRun && (broadcastRxThread != null)) {
            Log.d(TAG, "Stopping Broadcast Thread");
            hubIpRun = false;
            try {
                broadcastRxThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Thread already dead while waiting");
            }
        } else {
            Log.d(TAG, "Thread already stopped so not stopping");
        }
    }

    private static Thread pollHubThread = null;
    private static void mainPollHubThread () {
        pollHubThread = new Thread() {
            @Override
            public void run() {
                int pollCount = HUB_POLL_ATTEMPTS;
                while (hubPollRun && (pollCount > 0)) {
                    hubPollRun = false;
                }
            }
        };
    }
    static void pollHubStart () {
        if (pollHubThread == null || (pollHubThread.getState() == Thread.State.TERMINATED)) {
            mainPollHubThread();
        }
        if (pollHubThread.getState() == Thread.State.NEW) {
            Log.d(TAG, "Starting pollHub Thread");
            hubPollRun = true;
            pollHubThread.start();
        }
    }
    static void pollHubStop () {

        if (hubPollRun && (pollHubThread != null)) {
            Log.d(TAG, "Stopping pollHub Thread");
            hubPollRun = false;
            try {
                pollHubThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Thread already dead while waiting");
            }
        } else {
            Log.d(TAG, "Thread already stopped so not stopping");
        }
    }
    
}
