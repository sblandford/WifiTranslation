package eu.bkwsu.webcast.wifitranslation;


/*
  License from the project folder LICENSE file.
  MIT License

  Sourced from http://book2s.com/
 */

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static android.content.Context.AUDIO_SERVICE;
import static eu.bkwsu.webcast.wifitranslation.MainActivity.context;

public final class Tools {
    private static WifiManager.WifiLock mWifiHighPerfLock;
    private static WifiManager.MulticastLock mMulticastLock;

    public static boolean multicastLocked = false;
    public static boolean wifiModeFullHighPerf = false;

    private static AudioManager audioManager;

    private Tools(){}

    public synchronized static void setWifiOn (boolean on) {
        Context context = MainActivity.context;
        ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).setWifiEnabled(on);
    }
    public synchronized  static boolean isWifiOn () {
        Context context = MainActivity.context;
        int ip_address = ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        return (((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled() && (ip_address != 0));
    }
    public synchronized  static String showWifiInfo () {
        Context context = MainActivity.context;
        int ip_address = ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        return "IP: " + ip_address + ", " + ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().toString();
    }


    public synchronized  static void acquireWifiHighPerfLock() {
        Context context = MainActivity.context;
        if (mWifiHighPerfLock == null) {
            mWifiHighPerfLock = ((WifiManager)context.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Multicast_Translation");
            mWifiHighPerfLock.acquire();
        }
        wifiModeFullHighPerf = true;
    }

    public synchronized  static boolean isHighPerfLock() {
        if (mWifiHighPerfLock != null) {
            return mWifiHighPerfLock.isHeld();
        }
        return false;
    }

    public synchronized static void releaseWifiHighPerfLock() {
        if (mWifiHighPerfLock != null) {
            mWifiHighPerfLock.release();
            mWifiHighPerfLock = null;
        }
        wifiModeFullHighPerf = false;
    }

    public synchronized static void acquireMulticastLock(){
        Context context = MainActivity.context;
        if(mMulticastLock == null){
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            mMulticastLock = wifi.createMulticastLock("multicastLock");
            mMulticastLock.setReferenceCounted(false);
            mMulticastLock.acquire();
        }
        multicastLocked = true;
    }

    public synchronized static boolean isMulticastLock(){
        if(mMulticastLock != null){
            return mMulticastLock.isHeld();
        }
        return false;
    }

    public synchronized static void releaseMulticastLock(){
        if(mMulticastLock != null){
            mMulticastLock.release();
            mMulticastLock = null;
        }
        multicastLocked = false;
    }

    public synchronized static boolean blueToothScoOnly () {
        return (phones_type() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    public synchronized static boolean phones_check () {
        return (phones_type() != -1);
    }

    public synchronized static int phones_type () {
        audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (audioManager.isWiredHeadsetOn()) {
                return AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
            }
            if (audioManager.isBluetoothA2dpOn()) {
                return AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            }
        } else {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (int i = 0; i < devices.length; i++) {
                AudioDeviceInfo device = devices[i];
                int deviceType = device.getType();
                if (deviceType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return deviceType;
                }
            }
        }
        return -1;
    }

    // For debugging purposes
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes, int length) {
        char[] hexChars = new char[length * 4];
        for ( int j = 0; j < length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 4] = hexArray[v >>> 4];
            hexChars[j * 4 + 1] = hexArray[v & 0x0F];
            hexChars[j * 4 + 2] = ',';
            hexChars[j * 4 + 3] = ' ';
        }
        return new String(hexChars);
    }
    public static String bytesToHex(ByteBuffer bytes) {
        String hexChars = "";

        for ( int j = 0; j < bytes.position() ; j++ ) {
            hexChars += String.format("%02X", (0xFF & bytes.get(j))) + ", ";
        }

        return hexChars;
    }

    public static int getAudioFormatIntProp (String propertyName) {
        try {
            Class cls = Class.forName("android.media.AudioFormat");
            try {
                Field f = cls.getField(propertyName);
                if (! "int".equals(f.getType().toString())) {
                    throw new IllegalStateException("Attempt to access non-integer property of AudioFormat");
                }
                int fieldValue = Integer.parseInt(f.get(cls).toString());
                return fieldValue;
            } catch (Exception e) {
                throw new IllegalStateException("propertyNotFoundException: " + e.toString());
            }
        } catch (Exception e) {
            throw new IllegalStateException("classNotFoundException: " + e.toString());
        }
    }
}
