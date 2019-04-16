package eu.bkwsu.webcast.wifitranslation;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Properties;



final class RtspComms {
    private final String TAG = "RtspComms";

    private int RTSP_PORT;
    private int RTSP_MAX_RESPONSE_LENGTH;
    private int RTSP_REQUEST_TIMEOUT;
    private int RTP_UDP_TIMEOUT;
    private int RTP_PUNCH_NAT_COUNT;
    private int RTP_PUNCH_NAT_INTERVAL_MILLISECONDS;
    private int RTCP_INTERVAL_MILLISECONDS;


    private final String RTSP_VER = " RTSP/1.0";
    private final String RTSP_USER_AGENT = " WifiTranslationHub";

    private static final byte[] RTCP_HEADER_TEMPLATE = new byte[]{
            //For explanation see http://www.networksorcery.com/enp/protocol/rtcp.htm
            (byte)0x80, // Version, padding, report count = 0
            (byte)0xC9, // Receiver report
            0x00, 0x00, // Packet length in 32 bit words - 1 = 0
    };

    private int channel;
    private String rtspUrl;
    private InetAddress ip = null;
    private Socket sock;
    private int rtspClientPort = 0;
    private int rtcpClientPort = 0;
    private int rtcpServerPort = 0;
    private int rtpServerPort = 0;
    private String rtspSessionId = null;
    private DataOutputStream oos = null;
    private BufferedReader ois = null;

    private int cSeq = 1;
    private boolean playing = false;
    private boolean rtspRun = true;

    public RtspComms (Properties prop) {

        RTSP_PORT = Integer.parseInt(prop.getProperty("RTSP_PORT"));
        RTSP_MAX_RESPONSE_LENGTH = Integer.parseInt(prop.getProperty("RTSP_MAX_RESPONSE_LENGTH"));
        RTSP_REQUEST_TIMEOUT = Integer.parseInt(prop.getProperty("RTSP_REQUEST_TIMEOUT"));
        RTP_UDP_TIMEOUT = Integer.parseInt(prop.getProperty("RTP_UDP_TIMEOUT"));
        RTP_PUNCH_NAT_COUNT = Integer.parseInt(prop.getProperty("RTP_PUNCH_NAT_COUNT"));
        RTP_PUNCH_NAT_INTERVAL_MILLISECONDS = Integer.parseInt(prop.getProperty("RTP_PUNCH_NAT_INTERVAL_MILLISECONDS"));
        RTCP_INTERVAL_MILLISECONDS = Integer.parseInt(prop.getProperty("RTCP_INTERVAL_MILLISECONDS"));
    }

    public boolean startSession (int selectedChannel) {
        String hubIp = HubComms.getHostIp();

        channel = selectedChannel;

        rtspUrl = "rtsp://" + hubIp + ":" + RTSP_PORT + "/" + String.format("%02d", channel);
        try {
            ip = InetAddress.getByName(hubIp);
        } catch (UnknownHostException e) {
            Log.e(TAG, "Unable to find host : " + hubIp);
        }

        try {
            sock = new Socket(ip, RTSP_PORT);
            sock.setSoTimeout(RTSP_REQUEST_TIMEOUT);

            oos = new DataOutputStream(sock.getOutputStream());
            ois = new BufferedReader(new InputStreamReader(sock.getInputStream()));


        } catch (IOException e) {
            Log.e(TAG, "Unable to establish socket to " + hubIp + ", port " + RTSP_PORT + " : " + e.getMessage());
            return false;
        }

        if (options()) {
            Log.i(TAG, "Options OK");
        } else {
            return false;
        }
        cSeq++;
        if (describe()) {
            Log.i(TAG, "Describe OK");
        } else {
            return false;
        }
        cSeq++;
        if (setup()) {
            Log.i(TAG, "Setup OK with RTSP Client port : " + rtspClientPort + ", RTCP Server port : " + rtcpServerPort + ", Session ID : " + rtspSessionId);
        } else {
            return false;
        }
        cSeq++;
        if (play()) {
            Log.i(TAG, "Play OK");
            playing = true;
        } else {
            return false;
        }
        punchNat();
        rtcpThreadStart();
        return true;
    }


    public int getRtspClientPort () {
        return rtspClientPort;
    }
    public int getRtcpServerPort () {
        return rtcpServerPort;
    }
    public String getRtspSessionId () {
        return rtspSessionId;
    }

    public void rtspClose() {
        if (playing) {
            rtcpThreadStop();
            cSeq++;
            tearDown();
        }
        if (ois != null) {
            try {
                ois.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close RTSP input stream");
            }
        }
        if (oos != null) {
            try {
                oos.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close RTSP output stream");
            }
        }
        if (sock != null) {
            try {
                sock.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close RTSP socket");
            }
        }
    }

    private boolean options () {
        String msg = "OPTIONS " + rtspUrl + RTSP_VER + "\r\nCSeq: " + cSeq + "\r\nUser-Agent: " + RTSP_USER_AGENT + "\r\n\r\n";
        String response = sendReceive(msg);

        return keyWordCheck(response, new String[] {"DESCRIBE", "SETUP", "TEARDOWN", "PLAY", "Cseq.*" + cSeq});
    }
    private boolean describe () {
        String msg = "DESCRIBE " + rtspUrl + RTSP_VER + "\r\nAccept: application/sdp\r\nCSeq: " + cSeq + "\r\nUser-Agent: " + RTSP_USER_AGENT + "\r\n\r\n";
        String response = sendReceive(msg);

        return keyWordCheck(response, new String[] {"m=audio 0 RTP/AVP 97", "a=rtpmap:97 AMR-WB", "Cseq.*" + cSeq});
    }
    private boolean setup () {
        if (findRtspClientPort()) {
            String msg = "SETUP " + rtspUrl + RTSP_VER + "\r\nTransport: RTP/AVP/UDP;unicast;client_port=" + rtspClientPort + "-" + rtcpClientPort + "\r\nCSeq: " + cSeq + "\r\nUser-Agent: " + RTSP_USER_AGENT + "\r\n\r\n";
            String response = sendReceive(msg);
            if (!keyWordCheck(response, new String[] {"Transport: RTP/AVP/UDP;unicast", "Session:\\s*[0-9a-f]+", "server_port=[0-9]+-[0-9]+", "Cseq.*" + cSeq})) {
                Log.e(TAG, "Incorrect response to RTSP SETUP request");
                return false;
            }
            response = response.replace("\n", "|").replace("\r","");
            //Previous key word check should ensure that the result of replacefirst makes sense here
            rtspSessionId = response.replaceFirst(".*Session:\\s*([0-9a-f]+).*", "$1");
            rtcpServerPort = Integer.parseInt(response.replaceFirst(".*server_port=[0-9]+-([0-9]+).*", "$1"));
            rtpServerPort = Integer.parseInt(response.replaceFirst(".*server_port=([0-9]+)-[0-9]+.*", "$1"));
        } else {
            return false;
        }
        return true;
    }
    private boolean play () {
        String msg = "PLAY " + rtspUrl + RTSP_VER + "\r\nRange: npt=0.000-\r\nCSeq: " + cSeq + "\r\nUser-Agent: " + RTSP_USER_AGENT + "\r\nSession: " + rtspSessionId + "\r\n\r\n";
        String response = sendReceive(msg);

        return keyWordCheck(response, new String[] {"RTP-Info: requestPath=", "Session:\\s*" + rtspSessionId, "Cseq.*" + cSeq});
    }
    private void tearDown () {
        String msg = "TEARDOWN " + rtspUrl + RTSP_VER + "\r\nRange: npt=0.000-\r\nCSeq: " + cSeq + "\r\nUser-Agent: " + RTSP_USER_AGENT + "\r\nSession: " + rtspSessionId + "\r\n\r\n";
        String response = sendReceive(msg);

        if (keyWordCheck(response, new String[] {"Cseq.*" + cSeq})) {
            Log.i(TAG, "Successful RTSP Teardown request");
        } else {
            Log.w(TAG, "RTSP Teardown request unsuccessfull");
        }

    }

    //Send regular RTCP dummy pings to keep stream alive
    private Thread rtcpThread = null;
    private void mainrtcpThread () {
        rtcpThread = new Thread() {
            @Override
            public void run() {
                DatagramSocket rtcpSock = null;
                DatagramPacket rtcpPacket = new DatagramPacket(RTCP_HEADER_TEMPLATE, RTCP_HEADER_TEMPLATE.length, ip, rtcpServerPort);
                try {
                    rtcpSock = new DatagramSocket(null);
                    rtcpSock.setReuseAddress(true);
                    rtcpSock.setSoTimeout(RTP_UDP_TIMEOUT);
                    rtcpSock.bind(new InetSocketAddress(rtcpClientPort));
                } catch (IOException e) {
                    Log.w(TAG, "Unable to open RTCP : " + rtcpClientPort + " to Server Port : " + rtcpServerPort);
                    rtspRun = false;
                }
                while (rtspRun) {
                    try {
                        rtcpSock.send(rtcpPacket);
                    } catch (IOException e) {
                        Log.w(TAG, "Unable to send RTCP : " + rtcpClientPort + " to : " + rtcpServerPort);
                    }

                    //Sleep for a bit before re-sending in seconds
                    for (int i=0; i < RTCP_INTERVAL_MILLISECONDS  && rtspRun; i += 1000) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Active state thread interrupted");
                        }
                    }
                }
                if (rtcpSock != null) {
                    rtcpSock.close();
                }
            }
        };
    }
    void rtcpThreadStart () {
        if (rtcpThread == null || (rtcpThread.getState() == Thread.State.TERMINATED)) {
            mainrtcpThread();
        }
        if (rtcpThread.getState() == Thread.State.NEW) {
            Log.d(TAG, "Starting pollHub Thread");
            rtspRun = true;
            rtcpThread.start();
        }
    }
    void rtcpThreadStop () {
        if (rtspRun && (rtcpThread != null)) {
            Log.d(TAG, "Stopping RTCP Thread");
            rtspRun = false;
            try {
                rtcpThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Thread already dead while waiting");
            }
        } else {
            Log.d(TAG, "Thread already stopped so not stopping");
        }
    }

    public DatagramPacket punchPacket () {
        byte[] dummyPacket = TranslationTX.dummyRtpPacket();
        int dummyPacketLength = TranslationTX.dummyRtpPacketLength();
        DatagramPacket punchPacket = new DatagramPacket(dummyPacket, dummyPacketLength, ip, rtpServerPort);

        return punchPacket;
    }

    //Send a UDP packet from the RTP receiving port to the server to
    //attempt to establish a NAT pathway
    private void punchNat () {
        DatagramSocket punchSock;

        try {
            punchSock = new DatagramSocket(null);
            punchSock.setReuseAddress(true);
            punchSock.setSoTimeout(RTP_UDP_TIMEOUT);
            punchSock.bind(new InetSocketAddress(rtspClientPort));
            for (int i=0; i < RTP_PUNCH_NAT_COUNT; i++) {
                try {
                    Thread.sleep(RTP_PUNCH_NAT_INTERVAL_MILLISECONDS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Punch NAT interrupted");
                }
                punchSock.send(punchPacket());
            }
            punchSock.close();
        } catch (IOException e) {
            Log.w(TAG, "Unable to attempt NAT punch from RTSP Client port : " + rtspClientPort + " to Server Port : " + rtpServerPort);
        }
    }


    private boolean findRtspClientPort () {
        ServerSocket s = null;
        try {
            s = new ServerSocket(0);
            rtspClientPort = s.getLocalPort();
            rtcpClientPort = rtspClientPort + 1;
            s.close();
        } catch (IOException e) {
            Log.e(TAG, "Unable to get local client port for RTSP SETUP request");
            return false;
        }
        return true;
    }
    
    private String sendReceive (String msg) {
        String response = null;

        try {
            oos.write(msg.getBytes());
        } catch (IOException e) {
            Log.e(TAG, "Unable to send RTSP message : " + msg);
            return null;
        }
        try {
            int contentLength = 0;
            int charCount = 0;
            boolean inContent = false;
            //for (String line = ois.readLine(); ((line != null) && (!inContent)); line = ois.readLine()) {
            do {
                String line = ois.readLine();
                if (line == null) {
                    break;
                }
                if (line.matches("Content-Length:.*")) {
                    contentLength = Integer.parseInt(line.replaceAll("[^0-9]", ""));
                }
                if ("".equals(line)) {
                    inContent = true;
                    continue;
                }
                if (inContent) {
                    charCount += line.length() + 2; // The extra 2 is for line feed /n/r
                }
                if (response == null) {
                    response = new String(line);
                } else {
                    response = response.concat("\n" + line);
                }
                if ((response != null) && (response.length() > RTSP_MAX_RESPONSE_LENGTH)) {
                    Log.e(TAG, "Length of RTSP response of length " + response.length() + " exceeded limit of : " + RTSP_MAX_RESPONSE_LENGTH);
                    return null;
                }
            } while (!inContent || (charCount < contentLength));
        } catch (IOException e) {
            Log.e(TAG, "Unable to receive RTSP message in response to : " + msg);
            return null;
        }
        return response;
    }

    // Check for list of keywords in response. All must be present.
    private boolean keyWordCheck (String response, String[] keywords) {
        if (response == null) {
            return false;
        }

        response = response.replace("\n", "|").replace("\r","");

        for (String keyword: keywords) {
            if (!response.matches(".*" + keyword + ".*")) {
                return false;
            }
        }
        return true;
    }
}
