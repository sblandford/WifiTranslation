package eu.bkwsu.webcast.wifitranslation;

import android.content.Context;
import android.media.AudioRecord;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Properties;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

//CAPTURE TO AAC/AMR FILE COMMANDS
/*

#UDP packets to binary file
sudo tcpdump -i eno1 -n dst host 228.227.226.225 -X | grep -P "^\s0x" | grep -v 0x0000 | sed -r "s/^\s0x0010:(\s+[0-9a-f]{4}){6}/: /gi" | grep -Eo ":  [0-9a-f ]+ " | tr -d ":"  | xxd -r -p >dump.bin

#To play on ffmpeg/vlc etc, create an SDP file with the following content and play it
v=0
o=- 0 0 IN IP4 127.0.0.1
s=Tweet
c=IN IP4 228.227.226.225
t=0 0
a=tool:libavformat 56.40.101
m=audio 1234 RTP/AVP 97
b=AS:14
a=rtpmap:97 AMR-WB/16000/1
a=fmtp:97 octet-align=1


 */


public class TranslationTX {
    private static final String TAG = "TranslationTX";

    private static int MULTICAST_PORT;
    private static String MUTLICAST_IP_BASE;
    private static int MUTLICAST_UUID_OFFSET;
    private static int MULTICAST_TIMEOUT;
    private static int PACKET_BUFFER_SIZE;
    private static int SAMPLERATE;
    private static int BITRATE;
    private static String MIME;
    private static String CODEC_NAME;
    private static int CHANNELCONFIG;
    private static int AUDIO_CHANNELS;
    private static int AUDIO_ENCODING_FORMAT;
    //Audio buffer size: intended number of samples (1 sample = 2 bytes)
    private static int AUDIO_BUFF_SIZE;
    private static int TIMEOUT_US;
    private static MulticastSocket sock = null;
    //How often to send a log report
    private static int LOGFREQ;
    //How often to send a meter update
    private static int METERFREQ;
    //How fast meter should descend from peak
    private static int METER_DESCEND_RATE;
    //Time to wait for actions in seconds
    private static int ACTION_WAIT_TIMEOUT;

    private static float MAX_LEVEL_DB;
    private static float MAX_LEVEL;
    private static float MAX_HEADROOM;

    //Limits for Gain processor
    private static float MAX_GAIN_DB_LIMIT;
    private static float INCREASE_DB_PER_SECOND_LIMIT_LOW;
    private static float INCREASE_DB_PER_SECOND_LIMIT_HIGH;
    private static float HOLD_TIME_SECONDS_LIMIT_LOW;
    private static float HOLD_TIME_SECONDS_LIMIT_HIGH;
    private static double LIMITER_CURVE_CONSTANT;
    
    //Limits for Network packet redundancy
    private static int MAX_NETWORK_PACKET_REDUNDANCY;

    //RTP packets
    private static int MAX_PACKET_SIZE;
    private static int PACKET_REDUNDANCY_SPACE_MS;
    private static final byte[] AMR_WB_FRAME_SIZES = new byte[]{18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 6, 0, 0, 0, 1, 1};
    private static int AMR_WB_TIMESTAMP_INCREMENT;
    private static final int RTP_HEADER_SIZE = 12;
    private static final byte RTP_PAYLOAD_ID = 97;
    private static final byte[] RTP_HEADER_TEMPLATE = new byte[]{
            //For explanation see https://en.wikipedia.org/wiki/Real-time_Transport_Protocol#Packet_header
            (byte)0x80, (byte)0x80 | RTP_PAYLOAD_ID, //Version,P,X,CC,M,PT
            0x00, 0x00,             //Sequence number
            0x00, 0x00,             //Timestamp (use sample count for audio)
            0x00, 0x00,
            0x09, 0x1f,             //SSRC identifier
            0x54, (byte)0xcf
    };
    private static final byte RTP_TOC_HEADER = (byte) 0xF0;

    //Human-readable values for Gain processor with defaults
    private static float maxGainDb = 10.0f;
    private static float increaseDbPerSecond = 0.5f;
    private static float holdTimeSeconds = 0.5f;

    //Machine-useful values for gain processor calculated from human-readable values
    private static float maxGain;
    private static float gainIncreasePerSample;
    private static int holdTimeSamples;

    private static ByteBuffer rtpPayload;
    private static ByteBuffer rtpToc;
    private static int rtpSequenceNumber = 0;
    private static long rtpTimeStamp = 0;
    private static int rtpFrameCount = 0;

    private static int networkPacketRedundancy = 1;

    public enum Status {
        STOPPED, STARTING, RUNNING, MUTED
    }

    public enum Command {
        START, STOP
    }


    private static Context context;

    private static AudioRecord audioRecorder = null;
    private static int audioSessionId = 0;
    private static MediaCodec codec = null;

    private static Boolean txRun = false;

    private static int channel = -1;
    private static InetAddress multicastIpAddress, multicastUuidIpAddress;

    private static Status state = Status.STOPPED;
    private static boolean mute = false;

    private static boolean actionLock = false;

    private static float gain = 1.0f;
    private static float peak = 0.0f;
    private static int holdCounter = 0;

    private static boolean permissions_wait;


    public TranslationTX (Properties prop) {
        context = MainActivity.context;

        //Set from properties
        MULTICAST_PORT = Integer.parseInt(prop.getProperty("MULTICAST_PORT"));
        MUTLICAST_IP_BASE = prop.getProperty("MUTLICAST_IP_BASE");
        MULTICAST_TIMEOUT = Integer.parseInt(prop.getProperty("TX_MULTICAST_TIMEOUT"));
        MUTLICAST_UUID_OFFSET = Integer.parseInt(prop.getProperty("MUTLICAST_UUID_OFFSET"));
        PACKET_BUFFER_SIZE = Integer.parseInt(prop.getProperty("TX_PACKET_BUFFER_SIZE"));
        SAMPLERATE = Integer.parseInt(prop.getProperty("SAMPLERATE"));
        BITRATE = Integer.parseInt(prop.getProperty("AMR_BITRATE"));
        MIME = prop.getProperty("MIME");
        CODEC_NAME = prop.getProperty("TX_CODEC_NAME");
        AUDIO_CHANNELS = Integer.parseInt(prop.getProperty("AUDIO_CHANNELS"));
        CHANNELCONFIG = Tools.getAudioFormatIntProp(prop.getProperty("TX_CHANNELCONFIG"));
        AUDIO_ENCODING_FORMAT = Tools.getAudioFormatIntProp(prop.getProperty("AUDIO_ENCODING_FORMAT"));
        AUDIO_BUFF_SIZE = AudioRecord.getMinBufferSize(SAMPLERATE, CHANNELCONFIG , AUDIO_ENCODING_FORMAT);
        TIMEOUT_US = Integer.parseInt(prop.getProperty("TX_TIMEOUT_US"));
        LOGFREQ = Integer.parseInt(prop.getProperty("TX_LOGFREQ"));
        METERFREQ = Integer.parseInt(prop.getProperty("TX_METERFREQ"));
        METER_DESCEND_RATE = Integer.parseInt(prop.getProperty("TX_METER_DESCEND_RATE"));
        ACTION_WAIT_TIMEOUT = Integer.parseInt(prop.getProperty("ACTION_WAIT_TIMEOUT"));
        MAX_LEVEL_DB = Float.parseFloat(prop.getProperty("TX_MAX_LEVEL_DB"));
        //32767 is the maximum level of 16 bit PCM
        //Level (in Bells = deciBells / 10) is divided by two because dB relates to power and we are controlling voltage (P=(V^2)/R)
        MAX_LEVEL = 32767.0f * (float)Math.pow(10.0, (double)MAX_LEVEL_DB * 0.05);
        MAX_HEADROOM = 32767 - MAX_LEVEL;

        MAX_GAIN_DB_LIMIT = Float.parseFloat(prop.getProperty("TX_MAX_GAIN_DB_LIMIT"));
        INCREASE_DB_PER_SECOND_LIMIT_LOW = Float.parseFloat(prop.getProperty("TX_INCREASE_DB_PER_SECOND_LIMIT_LOW"));
        INCREASE_DB_PER_SECOND_LIMIT_HIGH = Float.parseFloat(prop.getProperty("TX_INCREASE_DB_PER_SECOND_LIMIT_HIGH"));
        HOLD_TIME_SECONDS_LIMIT_LOW = Float.parseFloat(prop.getProperty("TX_HOLD_TIME_SECONDS_LIMIT_LOW"));
        HOLD_TIME_SECONDS_LIMIT_HIGH = Float.parseFloat(prop.getProperty("TX_HOLD_TIME_SECONDS_LIMIT_HIGH"));
        LIMITER_CURVE_CONSTANT = Double.parseDouble(prop.getProperty("TX_LIMITER_CURVE_CONSTANT"));


        MAX_NETWORK_PACKET_REDUNDANCY = Integer.parseInt(prop.getProperty("TX_MAX_NETWORK_PACKET_REDUNDANCY"));
        MAX_PACKET_SIZE = Integer.parseInt(prop.getProperty("TX_MAX_PACKET_SIZE"));
        PACKET_REDUNDANCY_SPACE_MS = Integer.parseInt(prop.getProperty("TX_PACKET_REDUNDANCY_SPACE_MS"));
        AMR_WB_TIMESTAMP_INCREMENT = (int)(0.02 * (double)SAMPLERATE); // Each frame of AMR is 20ms

        rtpPayload = ByteBuffer.allocate(MAX_PACKET_SIZE);
        rtpToc = ByteBuffer.allocate(MAX_PACKET_SIZE);

        //Calculation from defaults
        calc_gain_params ();
    }

    private static void getAudioRecord () {
        Log.i(TAG, "Setting up audio record");
        audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLERATE, CHANNELCONFIG,
                AUDIO_ENCODING_FORMAT, AUDIO_BUFF_SIZE);

        if (audioRecorder == null || audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (audioRecorder == null) {
                Log.e(TAG, "Audiorecord is null");
            }
            if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Audiorecord is not initialized successfully");
            }
            throw new IllegalStateException("Could not create AudioRecord");
        }
        audioSessionId = audioRecorder.getAudioSessionId();

        audioRecorder.startRecording();
    }

    private static void getAudioCodec () {
        Log.i(TAG, "Setting up audio codec");

        try {
            codec = MediaCodec.createByCodecName(CODEC_NAME);
        }
        catch (IOException e) {
            throw new IllegalStateException("IOException " + e.toString());
        }
        MediaFormat format = MediaFormat.createAudioFormat(MIME, SAMPLERATE, AUDIO_CHANNELS);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFF_SIZE);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        codec.start();
    }

    private static void killAudioRecord () {
        if(audioRecorder != null && audioRecorder.getState() == AudioRecord.STATE_INITIALIZED){
            Log.i(TAG, "Closing audio record");
            audioRecorder.stop();
            audioRecorder.release();
            codec.stop();
            codec.release();
            audioRecorder = null;
            Tools.releaseWifiHighPerfLock();
        }
    }


    private static void peakLimiter (ByteBuffer buffer) {
        float sample, levelDiff, magnetude;
        short inSample, outSample;

        for (int i = 0; i < buffer.limit(); i += 2) {
            inSample = buffer.getShort(i);

            sample = (float)inSample;
            sample *= gain;
            magnetude = Math.abs(sample);
            if (magnetude > peak) {
                peak = (magnetude < 32767.0f)?magnetude:32767.0f;
            }
            levelDiff = magnetude - MAX_LEVEL;

            //Re-calculate gain
            if (levelDiff > 0) {
                //Over max level
                //Limit so Sine function remains in range
                if (levelDiff > MAX_HEADROOM) {
                    levelDiff = MAX_HEADROOM;
                }
                //Simulate soft clipping
                magnetude = MAX_LEVEL + levelDiff/(1 + (float)Math.exp((LIMITER_CURVE_CONSTANT * (double)levelDiff))/MAX_HEADROOM);
                //Hard clip
                if (magnetude > 32767) {
                    magnetude = 32767;
                }
                //Reset gain to whatever results in the magnetude from the original sample
                gain = magnetude / Math.abs((float)inSample);
                //Write signed result into sample
                sample = magnetude * ((inSample > 0)?1:-1);
                holdCounter = holdTimeSamples;
            } else {
                //Increase gain
                if (holdCounter > 0) {
                    --holdCounter;
                } else {
                    gain *= gainIncreasePerSample;
                    if (gain > maxGain) {
                        gain = maxGain;
                    }
                }
            }
            outSample = (short)sample;
            buffer.putShort(i,outSample);
        }
    }


    private static Thread txThread = null;
    private static void mainTXThread () {
        txThread = new Thread () {
            @Override
            public void run() {

                byte[] packetBuff = new byte[PACKET_BUFFER_SIZE];
                int inputBufferId, outputBufferId;
                int inLogCounter = LOGFREQ;
                int outLogCounter = LOGFREQ;
                int meterCounter = METERFREQ;
                int meterPointer = 0;

                Log.d(TAG, "TX Thread started");
                state = Status.STARTING;

                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

                getAudioRecord ();
                getAudioCodec();

                ByteBuffer[] codecInputBuffers = codec.getInputBuffers();
                ByteBuffer[] codecOutputBuffers = codec.getOutputBuffers();

                try {
                    sock = new MulticastSocket(MULTICAST_PORT);
                    sock.joinGroup(multicastIpAddress);
                    sock.setSoTimeout(MULTICAST_TIMEOUT);
                    //Main TX loop
                    while(txRun) {

                        // TODO Illegal state exception can happen here
                        inputBufferId = codec.dequeueInputBuffer(TIMEOUT_US);
                        if (inputBufferId >= 0) {
                            //Get buffer to read into
                            ByteBuffer pcmBuff = codecInputBuffers[inputBufferId];
                            //Read audio into buffer
                            audioRecorder.read(pcmBuff, AUDIO_BUFF_SIZE);
                            peakLimiter(pcmBuff);
                            if (--inLogCounter <= 0) {
                                Log.d(TAG, "Read PCM bytes: " + AUDIO_BUFF_SIZE);
                                inLogCounter = LOGFREQ;
                            }
                            //Mute if required (peakLimiter is still measuring/limiting when muted)
                            if (mute) {
                                state = Status.MUTED;
                                for (int i = 0; i < AUDIO_BUFF_SIZE; i++) {
                                    pcmBuff.put(i, (byte)0x00);
                                }
                                peak = 0;
                            } else {
                                state = Status.RUNNING;
                            }
                            //Write it out
                            codec.queueInputBuffer(inputBufferId, 0, AUDIO_BUFF_SIZE, 0, 0);

                            if (--meterCounter <= 0) {
                                if (meterPointer > 0) {
                                    meterPointer -= METER_DESCEND_RATE;
                                }
                                if (meterPointer < 0) {
                                    meterPointer = 0;
                                }
                                if ((int)peak > meterPointer) {
                                    meterPointer = (int)peak;
                                }
                                VuMeter.draw(meterPointer);
                                peak = 0.0f;
                                meterCounter = METERFREQ;
                            }
                        }

                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        outputBufferId = codec.dequeueOutputBuffer(bufferInfo,TIMEOUT_US);
                        if (outputBufferId >= 0) {
                             if (bufferInfo.size > 0) {
                                 ByteBuffer outputBuffer = codecOutputBuffers[outputBufferId];
                                 rtpPackerPusher(outputBuffer, sock);

                                 if (--outLogCounter <= 0) {

                                     Log.d(TAG,
                                            "Gain : " + gain +
                                            ", Peak : " + (int)peak + ", meterPointer : " + meterPointer +
                                            "\nBufferInfo flags : " + bufferInfo.flags +
                                            ", size : " + bufferInfo.size +
                                            ", offset : " + bufferInfo.offset);
                                    outLogCounter = LOGFREQ;
                                 }
                            }
                            codec.releaseOutputBuffer(outputBufferId,false);
                        }
                    }
                    killAudioRecord();
                    sock.close();
                    Tools.releaseMulticastLock();
                    Log.d(TAG, "TX Thread ending");
                }
                catch (IOException e)
                {
                    throw new IllegalStateException("IOException " + e.toString());
                }
            }
        };
    }

    private static void rtpPackerPusher (ByteBuffer frame, MulticastSocket sock) {
        final int frameSizeIndex = (frame.get(0) >> 3) & 0x0F;
        final int frameSize = AMR_WB_FRAME_SIZES[frameSizeIndex];

        //Check frame size is expected
        if (frame.limit() != frameSize) {
            Log.w(TAG, "AMR frame size of " + frameSize + " expected but " + frame.limit() + " found");
            return;
        }

        //Reached end of frame?
        if ((RTP_HEADER_SIZE +
                rtpToc.position() +
                frameSize +
                rtpPayload.position()) > MAX_PACKET_SIZE) {

            ByteBuffer rtpPacket = ByteBuffer.allocate(MAX_PACKET_SIZE);

            //RTP header
            rtpPacket.put(RTP_HEADER_TEMPLATE);
            //Insert sequence number and timestamp
            rtpPacket.put(2, (byte)((rtpSequenceNumber >>> 8) & (byte)0xFF));
            rtpPacket.put(3, (byte)(rtpSequenceNumber & (byte)0xFF));
            rtpPacket.put(4, (byte)((rtpTimeStamp >>> 24) & (byte)0xFF));
            rtpPacket.put(5, (byte)((rtpTimeStamp >>> 16) & (byte)0xFF));
            rtpPacket.put(6, (byte)((rtpTimeStamp >>> 8) & (byte)0xFF));
            rtpPacket.put(7, (byte)(rtpTimeStamp & (byte)0xFF));

            rtpSequenceNumber++;
            rtpTimeStamp += AMR_WB_TIMESTAMP_INCREMENT * rtpFrameCount;
            rtpFrameCount = 0;

            //TOC
            rtpToc.flip();
            rtpPacket.put(rtpToc);
            rtpToc.clear();

            //Payload
            rtpPayload.flip();
            rtpPacket.put(rtpPayload);
            rtpPayload.clear();

            int packetSize = rtpPacket.position();
            byte[] outData = new byte[packetSize];
            rtpPacket.flip();
            rtpPacket.get(outData);
            //Send same packet networkPacketRedundancy times
            for (int i = 0; i < networkPacketRedundancy; i++ ) {
                DatagramPacket pack = new DatagramPacket(outData, packetSize, multicastIpAddress, MULTICAST_PORT);
                try {
                    Tools.acquireMulticastLock();
                    Tools.acquireWifiHighPerfLock();
                    sock.send(pack);
                } catch (IOException e) {
                    throw new IllegalStateException("RTP send packet " + e.toString());
                }
                try {
                    Thread.sleep(PACKET_REDUNDANCY_SPACE_MS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Poll status interrupted");
                }
            }
            DatagramPacket pack = new DatagramPacket(new String("TX" + MainActivity.uuid).getBytes(), MainActivity.uuid.length() + 2, multicastUuidIpAddress, MULTICAST_PORT);
            try {
                Tools.acquireMulticastLock();
                Tools.acquireWifiHighPerfLock();
                sock.send(pack);
            } catch (IOException e) {
                throw new IllegalStateException("UUID send packet " + e.toString());
            }
        }

        //First byte of new TOC is 0xF0
        if (rtpToc.position() == 0) {
            rtpToc.put(RTP_TOC_HEADER);
        }
        if (rtpToc.position() > 1) {
            //Mark previous TOC entry as non-final
            rtpToc.put(rtpToc.position() - 1, (byte)(rtpToc.get(rtpToc.position() - 1) | (byte)0x80));
        }
        //Add new TOC entry
        rtpToc.put((byte)(frame.get(0) & 0x7F));

        //Add new payload
        //Discard first byte, we have added it to TOC already
        frame.get();
        //Add to frame to payload
        rtpPayload.put(frame);
        rtpFrameCount++;
    }

    private static void setChannelIp () {

        byte[] ipBaseAddressBytes, ipUuidBaseAddressBytes;
        int ipBaseAddressInt, ipUuidBaseAddressInt;

        try {
            multicastIpAddress = InetAddress.getByName(MUTLICAST_IP_BASE);
        }
        catch (UnknownHostException e) {
            throw new IllegalStateException("UnknownHostException " + e.toString());
        }
        ipBaseAddressBytes = multicastIpAddress.getAddress();

        ipBaseAddressInt = ByteBuffer.wrap(ipBaseAddressBytes).getInt() + channel;
        ipUuidBaseAddressInt = ByteBuffer.wrap(ipBaseAddressBytes).getInt() + channel + MUTLICAST_UUID_OFFSET;

        ipBaseAddressBytes = ByteBuffer.allocate(4).putInt(ipBaseAddressInt).array();
        ipUuidBaseAddressBytes = ByteBuffer.allocate(4).putInt(ipUuidBaseAddressInt).array();

        try {
            multicastIpAddress = InetAddress.getByAddress(ipBaseAddressBytes);
        }
        catch (UnknownHostException e) {
            throw new IllegalStateException("UnknownHostException " + e.toString());
        }
        try {
            multicastUuidIpAddress = InetAddress.getByAddress(ipUuidBaseAddressBytes);
        }
        catch (UnknownHostException e) {
            throw new IllegalStateException("UnknownHostException " + e.toString());
        }

        Log.d(TAG, "Listening on address : " + multicastIpAddress.toString() + ":" + MULTICAST_PORT);
    }


    private static void txStart () {

        if (channel >= 0) {
            if (txThread == null || (txThread.getState() == Thread.State.TERMINATED)) {
                mainTXThread();
            }
            if (txThread.getState() == Thread.State.NEW) {
                setChannelIp();

                Log.d(TAG, "Starting TX Thread");
                gain = maxGain;
                txRun = true;
                txThread.start();
            } else {
                Log.i(TAG, "Thread is not runnable and in state : " + txThread.getState());
            }
        } else {
            Log.i(TAG, "Channel has not yet been set so not starting TX thread");
        }
    }

    private static void txStop () {
        if (txRun && (txThread != null)) {
            Log.d(TAG, "Stopping TX Thread");
            txRun = false;
            try {
                txThread.join();
            } catch (InterruptedException e) {
                Log.d(TAG, "Thread already dead while waiting");
            }
        } else {
            Log.d(TAG, "Thread already stopped so not stopping");
        }
        state = Status.STOPPED;
    }

    private static synchronized void setActionLock (boolean state) {
        //Wait for previous lock release
        actionLock = state;
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

    private static void calc_gain_params () {
        maxGain = (float)Math.pow(10.0, (double)maxGainDb * 0.05);
        gainIncreasePerSample = (float)Math.pow(10.0, ((double)increaseDbPerSecond * 0.05) / (double)SAMPLERATE) ;
        holdTimeSamples = (int)(holdTimeSeconds * (float)SAMPLERATE);
    }


    public static void action (Command action) {
        actionWait();
        setActionLock(true);
        switch(action) {
            case START:
                txStart();
                break;
            case STOP:
                txStop();
                break;
        }
        setActionLock(false);
    }

    public static synchronized void channelSelect(int setChannel) {
        channel = setChannel;
        Log.d(TAG, "Selecting Channel : " + (channel + 1));
    }

    public static synchronized float getMaxGainDb() {
        return maxGainDb;
    }
    public static synchronized float getMaxGainDbMin() {
        return 0.0f;
    }
    public static synchronized float getMaxGainDbMax() {
        return MAX_GAIN_DB_LIMIT;
    }
    public static synchronized void setMaxGainDb(float maxGainDbSet) {
        if (maxGainDbSet < 0 ) {
            Log.w(TAG, "maxGainDb set to lower limit of 0, was : " + maxGainDbSet);
            maxGainDbSet = 0.0f;
        }
        if (maxGainDbSet > MAX_GAIN_DB_LIMIT ) {
            Log.w(TAG, "maxGainDb set to upper limit of " + MAX_GAIN_DB_LIMIT + ", was : " + maxGainDbSet);
            maxGainDbSet = MAX_GAIN_DB_LIMIT;
        }
        maxGainDb = maxGainDbSet;
        calc_gain_params();
    }

    public static synchronized float getIncreaseDbPerSecond() {
        return increaseDbPerSecond;
    }
    public static synchronized float getIncreaseDbPerSecondMin() {
        return INCREASE_DB_PER_SECOND_LIMIT_LOW;
    }
    public static synchronized float getIncreaseDbPerSecondMax() {
        return INCREASE_DB_PER_SECOND_LIMIT_HIGH;
    }
    public static synchronized void setIncreaseDbPerSecond(float increaseDbPerSecondSet) {
        if (increaseDbPerSecondSet < INCREASE_DB_PER_SECOND_LIMIT_LOW ) {
            Log.w(TAG, "increaseDbPerSecond set to lower limit of " + INCREASE_DB_PER_SECOND_LIMIT_LOW + ", was : " + increaseDbPerSecondSet);
            increaseDbPerSecondSet = INCREASE_DB_PER_SECOND_LIMIT_LOW;
        }
        if (increaseDbPerSecondSet > INCREASE_DB_PER_SECOND_LIMIT_HIGH ) {
            Log.w(TAG, "increaseDbPerSecond set to upper limit of " + INCREASE_DB_PER_SECOND_LIMIT_HIGH + ", was : " + increaseDbPerSecondSet);
            increaseDbPerSecondSet = INCREASE_DB_PER_SECOND_LIMIT_HIGH;
        }
        increaseDbPerSecond = increaseDbPerSecondSet;
        calc_gain_params();
    }

    public static synchronized float getHoldTimeSeconds() {
        return holdTimeSeconds;
    }
    public static synchronized float getHoldTimeSecondsMin() {
        return HOLD_TIME_SECONDS_LIMIT_LOW;
    }
    public static synchronized float getHoldTimeSecondsMax() {
        return HOLD_TIME_SECONDS_LIMIT_HIGH;
    }
    public static synchronized void setHoldTimeSeconds(float holdTimeSecondsSet) {
        if (holdTimeSecondsSet < HOLD_TIME_SECONDS_LIMIT_LOW ) {
            Log.w(TAG, "holdTimeSeconds set to lower limit of " + HOLD_TIME_SECONDS_LIMIT_LOW + ", was : " + holdTimeSecondsSet);
            holdTimeSecondsSet = HOLD_TIME_SECONDS_LIMIT_LOW;
        }
        if (holdTimeSecondsSet > HOLD_TIME_SECONDS_LIMIT_HIGH ) {
            Log.w(TAG, "holdTimeSeconds set to upper limit of " + HOLD_TIME_SECONDS_LIMIT_HIGH + ", was : " + holdTimeSecondsSet);
            holdTimeSecondsSet = HOLD_TIME_SECONDS_LIMIT_HIGH;
        }
        holdTimeSeconds = holdTimeSecondsSet;
        calc_gain_params();
    }

    public static synchronized void setNetworkPacketRedundancy(int networkPacketRedundancySet) {
        if (networkPacketRedundancySet < 1) {
            Log.w(TAG, "networkPacketRedundancy set to lower limit of 1");
            networkPacketRedundancySet = 1;
        }
        if (networkPacketRedundancySet > MAX_NETWORK_PACKET_REDUNDANCY) {
            Log.w(TAG, "networkPacketRedundancy set to upper limit of " + MAX_NETWORK_PACKET_REDUNDANCY);
            networkPacketRedundancySet = MAX_NETWORK_PACKET_REDUNDANCY;
        }
        networkPacketRedundancy = networkPacketRedundancySet;
    }
    public static synchronized int getNetworkPacketRedundancy() { return networkPacketRedundancy; }
    public static synchronized int getNetworkRedundancyMax () { return MAX_NETWORK_PACKET_REDUNDANCY; }

    public static synchronized Status state() {
        return state;
    }

    public static synchronized void mute(boolean setMute) { mute = setMute; }
}
