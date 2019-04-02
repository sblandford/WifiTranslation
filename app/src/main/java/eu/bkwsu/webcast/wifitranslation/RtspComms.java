package eu.bkwsu.webcast.wifitranslation;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Properties;

final class RtspComms {
    private static final String TAG = "RtspComms";

    private static int RTSP_PORT;
    private static int RTSP_MAX_RESPONSE_LENGTH;
    private static int RTSP_REQUEST_TIMEOUT;
    
    private final static String RTSP_VER = " RTSP/1.0";
    private final static String RTSP_USER_AGENT = " WifiTranslationHub";

    

    private static int channel;
    private static String rtspUrl;
    private static InetAddress ip = null;
    private static Socket sock;
    DataOutputStream oos = null;
    BufferedReader ois = null;

    private static int cSeq = 1;
    private static String sessionId;

    public RtspComms (Properties prop, int selectedChannel) {

        RTSP_PORT = Integer.parseInt(prop.getProperty("RTSP_PORT"));
        RTSP_MAX_RESPONSE_LENGTH = Integer.parseInt(prop.getProperty("RTSP_MAX_RESPONSE_LENGTH"));
        RTSP_REQUEST_TIMEOUT = Integer.parseInt(prop.getProperty("RTSP_REQUEST_TIMEOUT"));

        String hubIp = HubComms.getHostIp();
        rtspUrl = "rtsp://" + hubIp + ":" + RTSP_PORT + "/" + String.format("%02d", selectedChannel);
        try {
            ip = InetAddress.getByName(hubIp);
        } catch (UnknownHostException e) {
            Log.e(TAG, "Unable to find host : " + hubIp);
        }
        channel = selectedChannel;

        try {
            sock = new Socket(ip, RTSP_PORT);
            sock.setSoTimeout(RTSP_REQUEST_TIMEOUT);

            oos = new DataOutputStream(sock.getOutputStream());
            ois = new BufferedReader(new InputStreamReader(sock.getInputStream()));


        } catch (IOException e) {
            Log.e(TAG, "Unable to establish socket to " + hubIp + ", port " + RTSP_PORT + " : " + e.getMessage());
            return;
        }

        if (options()) {
            Log.i(TAG, "Options look OK");
        } else {
            return;
        }
        cSeq++;
        if (describe()) {
            Log.i(TAG, "Describe looks OK");
        } else {
            return;
        }
        cSeq++;
        
    }

    public void rtspClose() {
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

        response = response.replace("\n", "").replace("\r","");

        for (String keyword: keywords) {
            if (!response.matches(".*" + keyword + ".*")) {
                return false;
            }
        }
        return true;
    }

}
