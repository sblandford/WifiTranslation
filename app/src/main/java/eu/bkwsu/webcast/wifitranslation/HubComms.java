package eu.bkwsu.webcast.wifitranslation;

import android.util.Log;

import org.json.JSONArray;
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
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Integer.parseInt;

public class HubComms {
    private static final String TAG = "HubComms";

    private static volatile boolean hubIpRun = false;
    private static volatile boolean hubFound = false;
    private static volatile boolean hubPollRun = false;
    private static volatile boolean hubByHostname = false;
    private static volatile String hubWanProtocol = "http";
    private static volatile String hubLanProtocol = "http";
    private static volatile String hubProtocol = "http";
    private static volatile String hubHostName = "";
    private static volatile String hubIp = "";
    private static volatile int hubWebPort = 0;
    private static volatile int hubPortIndex = 0;
    private static volatile boolean triedAllPorts = false;

    private static int HUB_BROADCAST_LENGTH_MAX;
    private static int HUB_BROADCAST_LENGTH_MIN;
    private static int HUB_BROADCAST_PORT;
    private static int HUB_BROADCAST_TIMEOUT;
    private static String HUB_HOSTNAME;
    private static int HUB_POLL_REQUEST_TIMEOUT;
    private static int HUB_POLL_LOSS_COUNT;
    private static int HUB_POLL_INTERVAL_MILLISECONDS;
    private static int HUB_POLL_RETRY_INTERVAL_MILLISECONDS;
    private static String HUB_STAT_PATH;
    private static String HUB_STAT_PORTS[];
    private static int PACKET_BUFFER_SIZE;

    private static volatile  Map<Integer, AppState.Chan> latestChannelMap;

    public HubComms (Properties prop) {
        HUB_BROADCAST_LENGTH_MAX = parseInt(prop.getProperty("HUB_BROADCAST_LENGTH_MAX"));
        HUB_BROADCAST_LENGTH_MIN = parseInt(prop.getProperty("HUB_BROADCAST_LENGTH_MIN"));
        HUB_BROADCAST_TIMEOUT = parseInt(prop.getProperty("HUB_BROADCAST_TIMEOUT"));
        HUB_BROADCAST_PORT = parseInt(prop.getProperty("HUB_BROADCAST_PORT"));
        HUB_HOSTNAME = prop.getProperty("HUB_HOSTNAME");
        HUB_POLL_REQUEST_TIMEOUT = parseInt(prop.getProperty("HUB_POLL_REQUEST_TIMEOUT"));
        HUB_POLL_LOSS_COUNT = parseInt(prop.getProperty("HUB_POLL_LOSS_COUNT"));
        HUB_POLL_INTERVAL_MILLISECONDS = parseInt(prop.getProperty("HUB_POLL_INTERVAL_MILLISECONDS"));
        HUB_POLL_RETRY_INTERVAL_MILLISECONDS = parseInt(prop.getProperty("HUB_POLL_RETRY_INTERVAL_MILLISECONDS"));
        HUB_STAT_PATH = prop.getProperty("HUB_STAT_PATH");
        HUB_STAT_PORTS = prop.getProperty("HUB_STAT_PORTS").split(",");
        PACKET_BUFFER_SIZE = parseInt(prop.getProperty("RX_PACKET_BUFFER_SIZE"));
    }


    //The Hub will send a broadcast packet every few seconds to signal its WAN Protocol, LAN Protocol, Hostname, IP address, HTTP port
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

                    //Keep a socket open to listen to all the UDP trafic that is destined for this port
                    sock = new DatagramSocket(null);
                    sock.setReuseAddress(true);
                    sock.setBroadcast(true);
                    sock.bind(new InetSocketAddress(HUB_BROADCAST_PORT));
                    sock.setBroadcast(true);

                    while(hubIpRun) {
                        DatagramPacket pack = new DatagramPacket(packetBuff, PACKET_BUFFER_SIZE);
                        sock.setSoTimeout(HUB_BROADCAST_TIMEOUT);
                        sock.receive(pack);
                        packetLength = pack.getLength();

                        if ((packetLength >= HUB_BROADCAST_LENGTH_MIN ) && (packetLength <= HUB_BROADCAST_LENGTH_MAX )){
                            Log.d(TAG, "Broadcast packet received with length : " + packetLength);

                            String hubMsg[] = new String(packetBuff, 0, packetLength, Charset.forName("US-ASCII")).split(" ");
                            if (hubMsg.length == 5) {
                                if (hubMsg[0].matches("^https?$")) {
                                    hubWanProtocol = hubMsg[0];
                                } else {
                                    Log.w(TAG,"Unable to parse broadcast parameter: Hub WAN Protocol : " + hubMsg[0]);
                                }
                                if (hubMsg[1].matches("^https?$")) {
                                    hubLanProtocol = hubMsg[1];
                                } else {
                                    Log.w(TAG,"Unable to parse broadcast parameter: Hub LAN Protocol : " + hubMsg[1]);
                                }
                                hubHostName = hubMsg[2];
                                if (hubMsg[3].matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
                                    hubIp = hubMsg[3];
                                } else {
                                    Log.w(TAG,"Unable to parse broadcast parameter: Hub IP : " + hubMsg[3]);
                                }
                                if (hubMsg[4].matches("^[0-9]{1,5}$") && (parseInt(hubMsg[4]) <= 65535)) {
                                    hubWebPort = parseInt(hubMsg[4]);
                                } else {
                                    Log.w(TAG,"Unable to parse broadcast parameter: Hub Web port : " + hubMsg[4]);
                                }
                                Log.i(TAG, "Hub information detected from broadcast : WAN Proto : " + hubWanProtocol +
                                        ", LAN Proto : " + hubLanProtocol + ", Hostname : " + hubHostName +
                                        ", IP : " + hubIp + ", Web Port : " + Integer.toString(hubWebPort));
                                //Mission accomplished so stop
                                hubProtocol = hubLanProtocol;
                                hubIpRun = false;
                                hubFound = true;
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                    Log.d(TAG, "Hub broadcast time out");
                } catch (IOException e) {
                    throw new IllegalStateException("IOException " + e.toString());
                }
            }
        };
    }
    static void broadcastStart () {
        if (hubFound) {
            return;
        }
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
                HttpURLConnection urlConnection = null;



                while (hubPollRun) {
                    long startTime;
                    int pollLossCounter = HUB_POLL_LOSS_COUNT;
                    hubPortIndex = 0;
                    triedAllPorts = false;

                    try {
                        InetAddress address = InetAddress.getByName(HUB_HOSTNAME);
                        hubIp = address.getHostAddress();
                        Log.i(TAG, "Hub IP address detected by name resolution : " + hubIp);
                    } catch (UnknownHostException e) {
                        Log.i(TAG, "Unable to resolve Hub by name : " + HUB_HOSTNAME);
                        // Don't even try it if name doesn't resolve
                        triedAllPorts = true;
                    }


                    while (hubPollRun && (hubFound || !triedAllPorts)) {
                        if (fetchJson(hubProtocol, hubHostName, hubIp, hubWebPort)) {
                            // Successful poll of hub
                            if (!hubFound) {
                                Log.i(TAG, "Successfully polled hub");
                            }
                            hubFound = true;
                            triedAllPorts = true;
                            pollLossCounter = HUB_POLL_LOSS_COUNT;
                            //Sleep for a bit before re-polling
                            try {
                                Thread.sleep(HUB_POLL_INTERVAL_MILLISECONDS);
                            } catch (InterruptedException e) {
                                Log.d(TAG, "Active state thread interrupted");
                            }
                        } else {
                            // If we haven't received a broadcast message from the Hub
                            // then keep trying different protocol and port combinations
                            if (!hubFound && !triedAllPorts) {
                                String portText = (HUB_STAT_PORTS[hubPortIndex++]);
                                String portClue = portText.substring(portText.length() - 1);
                                hubProtocol = ("3".equals(portClue)) ? "https" : "http";
                                hubWebPort = Integer.parseInt(portText);
                                if (hubPortIndex >= HUB_STAT_PORTS.length) {
                                    triedAllPorts = true;
                                }
                                Log.i(TAG, "Trying to find Hub : WAN Proto : " + hubWanProtocol +
                                        ", LAN Proto : " + hubProtocol + ", Hostname : " + hubHostName +
                                        ", IP : " + hubIp + ", Web Port : " + Integer.toString(hubWebPort));
                            }
                            if (triedAllPorts) {
                                // Poll was unsuccessful. Count down before giving up and re-searching
                                if (--pollLossCounter < 0) {
                                    hubFound = false;
                                    latestChannelMap = null;
                                } else {
                                    Log.d(TAG, "Hub poll unsuccessfull, attempts left : " + pollLossCounter);
                                }
                            }
                            try {
                                //Wait a second
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Log.d(TAG, "Active state thread interrupted");
                            }
                        }

                    }
                    // Wait for timeout if hub still not found
                    startTime = System.currentTimeMillis();
                    while (hubPollRun && (!hubFound) && ((System.currentTimeMillis() - startTime) < HUB_POLL_RETRY_INTERVAL_MILLISECONDS)) {
                        try {
                            //Wait a second
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Active state thread interrupted");
                        }
                    }
                    if (hubPollRun) {
                        Log.i(TAG, "Restarting search for for Hub");
                    }
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
            hubProtocol = "http";
            hubHostName = HUB_HOSTNAME;
            if (!triedAllPorts) {
                hubWebPort = Integer.parseInt(HUB_STAT_PORTS[hubPortIndex]);
            }
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

    private static boolean fetchJson (String protocol, String hostname, String ip, int port) {
        HttpURLConnection urlConnection = null;
        InputStream in = null;
        StringBuilder result = new StringBuilder();
        boolean success = true;

        //Return if no valid IP address
        if (!ip.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) {
            return false;
        }

        String urlText = protocol + "://" + ("https".equals(protocol)?hostname:ip) + ":" + port + "/" + HUB_STAT_PATH;

        try {
            URL url = new URL(urlText);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.setRequestProperty("Content-length", "0");
            urlConnection.setAllowUserInteraction(false);
            urlConnection.setConnectTimeout(HUB_POLL_REQUEST_TIMEOUT);
            urlConnection.setReadTimeout(HUB_POLL_REQUEST_TIMEOUT);
            in = new BufferedInputStream(urlConnection.getInputStream());

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }

            Pattern pattern = Pattern.compile("\\((.*)\\)");
            Matcher matcher = pattern.matcher(result);
            if (matcher.find())
            {
                Log.i(TAG, matcher.group(1));
                success = parseJson(matcher.group(1));
                //https://stackoverflow.com/questions/9605913/how-to-parse-json-in-android?utm_medium=organic&utm_source=google_rich_qa&utm_campaign=google_rich_qa

                JSONObject jObject = new JSONObject(matcher.group(1));
                Log.i(TAG, "Number of channels : " + jObject.length());
                JSONObject jObject0 = jObject.getJSONObject("0");
                Log.i(TAG,jObject0.toString());
            } else {
                success = false;
            }

        } catch( Exception e) {
            Log.d(TAG,"Failed to read from : " + urlText);
            success = false;
        } finally {
            if (urlConnection != null) {
                try {
                    urlConnection.disconnect();
                } catch (Exception e) {
                    Log.w(TAG, "Unable to close JSON input connection");
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch ( Exception e) {
                    Log.w(TAG, "Unable to close JSON input stream");
                }
            }
        }

        return success;
    }

    private static boolean parseJson (String json) {
        int numChans;
        Map<Integer, AppState.Chan> channelMap = new ConcurrentHashMap<>();

        try {
            Log.d(TAG, json);
            JSONObject jObject = new JSONObject(json);
            numChans = jObject.length();
            Log.d(TAG, "Number of channels : " + numChans);
            for (int i = 0; i < numChans; i++) {
                AppState.Chan channel = new AppState.Chan();
                if (jObject.has(Integer.toString(i))) {
                    JSONObject jObjectChannel = jObject.getJSONObject(Integer.toString(i));
                    channel.viewId = -1;
                    if (jObjectChannel.has("busy")) {
                        channel.busy = jObjectChannel.getBoolean("busy");
                    } else {
                        channel.busy = true; // Assume the worst if channel status not found
                    }
                    if (jObjectChannel.has("valid")) {
                        channel.valid = jObjectChannel.getBoolean("valid");
                    } else {
                        channel.valid = false;
                    }
                    if (jObjectChannel.has("open")) {
                        channel.open = jObjectChannel.getBoolean("open");
                    } else {
                        channel.open = false;
                    }
                    if (jObjectChannel.has("name")) {
                        channel.name = jObjectChannel.getString("name");
                    } else {
                        channel.name = String.format("%s%4d", "? ", i + 1); // Indicate distaste at not being able to get channel name
                    }
                    if (jObjectChannel.has("allowedIds")) {
                        JSONArray allowedIdsJson = jObjectChannel.getJSONArray("allowedIds");
                        channel.allowedIds.clear();
                        for (int j=0; j < allowedIdsJson.length(); j++) {
                            channel.allowedIds.add(allowedIdsJson.getString(j));
                        }
                    }
                    channelMap.put(i, channel);
                } else {
                    throw new NoSuchFieldException ("Not found expected channel number in JSON : " + i);
                }
            }
            latestChannelMap = channelMap;
        } catch ( Exception e) {
            Log.e(TAG, "Unable to parse Hub stat JSON : " + e.getMessage());
            return false;
        }
        return true;
    }

    static Map<Integer, AppState.Chan> getChannelMap() {
        return latestChannelMap;
    }

    static String getHostIp () { return hubIp;}
}
