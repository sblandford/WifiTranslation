package eu.bkwsu.webcast.wifitranslation;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HubComms {
    private static final String TAG = "HubComms";

    private static boolean hubIpRun = false;
    private static boolean hubPollRun = false;
    private static boolean hubByHostname = false;
    private static String hubIp = "";

    private static int HUB_BROADCAST_LENGTH_MAX;
    private static int HUB_BROADCAST_LENGTH_MIN;
    private static int HUB_BROADCAST_PORT;
    private static String HUB_HOSTNAME;
    private static int HUB_POLL_ATTEMPTS;
    private static int HUB_POLL_INTERVAL_MILLISECONDS;
    private static String HUB_PROTOCOL;
    private static String HUB_STAT_PATH;
    private static int HUB_STAT_PORT;
    private static int PACKET_BUFFER_SIZE;

    public HubComms (Properties prop) {
        HUB_BROADCAST_LENGTH_MAX = Integer.parseInt(prop.getProperty("HUB_BROADCAST_LENGTH_MAX"));
        HUB_BROADCAST_LENGTH_MIN = Integer.parseInt(prop.getProperty("HUB_BROADCAST_LENGTH_MIN"));
        HUB_BROADCAST_PORT = Integer.parseInt(prop.getProperty("HUB_BROADCAST_PORT"));
        HUB_HOSTNAME = prop.getProperty("HUB_HOSTNAME");
        HUB_POLL_ATTEMPTS = Integer.parseInt(prop.getProperty("HUB_POLL_ATTEMPTS"));
        HUB_POLL_INTERVAL_MILLISECONDS = Integer.parseInt(prop.getProperty("HUB_POLL_INTERVAL_MILLISECONDS"));
        HUB_PROTOCOL = prop.getProperty("HUB_PROTOCOL");
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

                    if (!hubByHostname) {
                        try {
                            InetAddress address = InetAddress.getByName(HUB_HOSTNAME);
                            hubIp = address.getHostAddress();
                            Log.i(TAG, "Hub IP address detected by name resolution : " + hubIp);
                            hubIpRun = false;
                            hubByHostname = true;
                            pollHubStart();
                        } catch (UnknownHostException e) {
                            Log.i(TAG, "Unable to resolve Hub by name : " + HUB_HOSTNAME);
                        }
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
                HttpURLConnection urlConnection = null;
                
                while (hubPollRun && (pollCount > 0)) {

                    StringBuilder result = new StringBuilder();

                    try {
                        URL url = new URL(((hubByHostname)?HUB_PROTOCOL + "://" + HUB_HOSTNAME:"http://" + hubIp)+ ":" + HUB_STAT_PORT + "/" + HUB_STAT_PATH);
                        urlConnection = (HttpURLConnection) url.openConnection();
                        InputStream in = new BufferedInputStream(urlConnection.getInputStream());

                        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

                        String line;
                        while ((line = reader.readLine()) != null) {
                            result.append(line);
                        }
                        pollCount = HUB_POLL_ATTEMPTS;

                        Pattern pattern = Pattern.compile("\\((.*)\\)");
                        Matcher matcher = pattern.matcher(result);
                        if (matcher.find())
                        {
                            //https://stackoverflow.com/questions/9605913/how-to-parse-json-in-android?utm_medium=organic&utm_source=google_rich_qa&utm_campaign=google_rich_qa
                            Log.i(TAG, matcher.group(1));
                            JSONObject jObject = new JSONObject(matcher.group(1));
                            //JSONObject jObject0 = jObject.getJSONObject("0");
                            //Log.i(TAG,jObject0.toString());
                        }

                    }catch( Exception e) {
                        Log.d(TAG,"Attempt " + (HUB_POLL_ATTEMPTS - pollCount) + ": Failed to read " + HUB_PROTOCOL + "://" + HUB_HOSTNAME + ":" + HUB_STAT_PORT + "/" + HUB_STAT_PATH);
                        pollCount--;
                    } finally {
                        if (urlConnection != null) {
                            urlConnection.disconnect();
                        }
                    }

                    //Sleep for a bit
                    try {
                        Thread.sleep(HUB_POLL_INTERVAL_MILLISECONDS);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Active state thread interrupted");
                    }
                }
                //If we hostname-based fetch failed then try looking for broadcast of IP instead
                if ((pollCount == 0) && hubByHostname) {
                    broadcastStart ();
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
