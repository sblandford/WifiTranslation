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
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static android.content.Context.AUDIO_SERVICE;
import static android.content.Context.WIFI_SERVICE;
import static eu.bkwsu.webcast.wifitranslation.MainActivity.context;

final class Tools {
    private static WifiManager.WifiLock mWifiHighPerfLock;
    private static WifiManager.MulticastLock mMulticastLock;

    static boolean multicastLocked = false;
    static boolean wifiModeFullHighPerf = false;

    private static AudioManager audioManager;

    private Tools(){}

    synchronized static void setWifiOn (boolean on) {
        Context context = MainActivity.context;
        ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).setWifiEnabled(on);
    }
    synchronized  static boolean isWifiOn () {

        final int WIFI_AP_STATE_ENABLED   = 13;

        Context context = MainActivity.context;

        //Detect if Hotspot is enabled if possible
        final WifiManager wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        try {
            Method method = (wifiManager.getClass().getDeclaredMethod("getWifiApState"));
            method.setAccessible(true);
            try {
                int actualState = (Integer) method.invoke(wifiManager, (Object[]) null);
                if (actualState == WIFI_AP_STATE_ENABLED) {
                    return true;
                }
            } catch (InvocationTargetException e) {
                // Pass
            } catch (IllegalAccessException e) {
                // Pass
            }
        } catch (NoSuchMethodException e) {
            // Pass
        }

        //Otherwise detect if normal Wifi is enabled
        int ip_address = ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        return (((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).isWifiEnabled() && (ip_address != 0));
    }
    synchronized  static String showWifiInfo () {
        Context context = MainActivity.context;

        int ip_address = ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getIpAddress();
        return "IP: " + ip_address + ", " + ((WifiManager)context.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().toString();
    }


    synchronized  static void acquireWifiHighPerfLock() {
        Context context = MainActivity.context;
        if (mWifiHighPerfLock == null) {
            mWifiHighPerfLock = ((WifiManager)context.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Multicast_Translation");
            mWifiHighPerfLock.acquire();
        }
        wifiModeFullHighPerf = true;
    }

    synchronized  static boolean isHighPerfLock() {
        if (mWifiHighPerfLock != null) {
            return mWifiHighPerfLock.isHeld();
        }
        return false;
    }

    synchronized static void releaseWifiHighPerfLock() {
        if (mWifiHighPerfLock != null) {
            mWifiHighPerfLock.release();
            mWifiHighPerfLock = null;
        }
        wifiModeFullHighPerf = false;
    }

    synchronized static void acquireMulticastLock(){
        Context context = MainActivity.context;
        if (mMulticastLock == null) {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            mMulticastLock = wifi.createMulticastLock("multicastLock");
            mMulticastLock.setReferenceCounted(false);
            mMulticastLock.acquire();
        } else if (!mMulticastLock.isHeld()) {
            mMulticastLock.acquire();
        }
        multicastLocked = true;
    }

    synchronized static boolean isMulticastLock(){
        if(mMulticastLock != null){
            return mMulticastLock.isHeld();
        }
        return false;
    }

    synchronized static void releaseMulticastLock(){
        if(mMulticastLock != null){
            mMulticastLock.release();
            mMulticastLock = null;
        }
        multicastLocked = false;
    }

    synchronized static boolean blueToothScoOnly () {
        return (phones_type() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
    }

    synchronized static boolean phones_check () {
        return (phones_type() != -1);
    }

    synchronized static int phones_type () {
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
                        || deviceType == AudioDeviceInfo.TYPE_USB_HEADSET
                        || deviceType == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || deviceType == AudioDeviceInfo.TYPE_AUX_LINE
                        || deviceType == AudioDeviceInfo.TYPE_LINE_ANALOG
                        || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return deviceType;
                }
            }
        }
        return -1;
    }

    synchronized static void phones_mode_set () {
        audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        //No speakerphone if headphones in
        audioManager.setSpeakerphoneOn(!phones_check());

        if (blueToothScoOnly()) {
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }
    }

    // For debugging purposes
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    static String bytesToHex(byte[] bytes, int length) {
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
    static String bytesToHex(ByteBuffer bytes) {
        String hexChars = "";

        for ( int j = 0; j < bytes.position() ; j++ ) {
            hexChars += String.format("%02X", (0xFF & bytes.get(j))) + ", ";
        }

        return hexChars;
    }

    static int getAudioFormatIntProp (String propertyName) {
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

    /**
     *  Unique view id generator, like the one used in {@link View} class for view id generation.
     *  Since we can't access the generator within the {@link View} class before API 17, we create
     *  the same generator here. This creates a problem of two generator instances not knowing about
     *  each other, and we need to take care that one does not generate the id already generated by other one.
     *
     *  We know that all integers higher than 16 777 215 are reserved for aapt-generated identifiers
     *  (source: {@link View#generateViewId()}, so we make sure to never generate a value that big.
     *  We also know that generator within the {@link View} class starts at 1.
     *  We set our generator to start counting at 15 000 000. This gives us enough space
     *  (15 000 000 - 16 777 215), while making sure that generated IDs are unique, unless View generates
     *  more than 15M IDs, which should never happen.
     */
    private static final AtomicInteger viewIdGenerator = new AtomicInteger(15000000);    /**
     * Generate a value suitable for use in {@link View#setId(int)}.
     * This value will not collide with ID values generated at build time by aapt for R.id.
     *
     * @return a generated ID value
     */
    static int generateViewId() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return generateUniqueViewId();
        } else {
            return View.generateViewId();
        }
    }

    private static int generateUniqueViewId() {
        while (true) {
            final int result = viewIdGenerator.get();
            // aapt-generated IDs have the high byte nonzero; clamp to the range under that.
            int newValue = result + 1;
            if (newValue > 0x00FFFFFF) newValue = 1; // Roll over to 1, not 0.
            if (viewIdGenerator.compareAndSet(result, newValue)) {
                return result;
            }
        }
    }
}
