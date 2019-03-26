package eu.bkwsu.webcast.wifitranslation;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.util.Log;

import static android.content.Context.AUDIO_SERVICE;

//https://github.com/taehwandev/MediaCodecExample/blob/master/src/net/thdev/mediacodecexample/decoder/AudioDecoderThread.java

//UDP listener example
//https://gist.github.com/finnjohnsen/3654994

//OMX Error codes
//https://android.googlesource.com/platform/frameworks/native/+/jb-mr1-dev/include/media/openmax/OMX_Core.h
//https://android.googlesource.com/platform/frameworks/av/+/913efd2bb99a056eb44395a93c6aa361a96dde6a/include/media/stagefright/MediaErrors.h
//https://android.googlesource.com/platform/frameworks/av/+/6dc6c38/media/libstagefright/MediaCodec.cpp
//https://android.googlesource.com/platform/frameworks/base/+/4484bdd2f99a753b0801f0c13ca8a2b7bc38695a/include/media/stagefright/MediaCodec.h

//To generate test tone with ffmpeg:
//ffmpeg -re -f lavfi -i "sine=frequency=864:sample_rate=16000" -c:a libvo_amrwbenc -b:a 14250 -f rtp rtp://@228.227.226.225:1234

//To receive UUIDs in bash terminal continuously
//socat UDP4-RECVFROM:1234,ip-add-membership=228.227.227.225:0.0.0.0,fork -

//To send UUID from bash terminal to allow access
//echo -n "rx42b06595-6d85-4334-946a-6dbd4c2cfe7f" | socat - UDP:228.227.227.225:1234


// RTSP Player info
// https://android--examples.blogspot.com/2017/08/android-play-audio-from-url-http.html
// https://developer.android.com/reference/android/media/MediaPlayer#setDataSource(java.lang.String)


public class TranslationRX {
    private static final String TAG = "TranslationRX";

    private static int MULTICAST_PORT;
    private static String MUTLICAST_IP_BASE;
    private static int MUTLICAST_UUID_OFFSET;
    private static int MUTLICAST_UUID_MS;
    private static int MUTLICAST_UUID_JITTER_MS;
    private static int MULTICAST_TIMEOUT;
    private static int RTSP_PORT;
    private static int MAX_MULTICAST_TIMEOUTS_BEFORE_KICK;
    private static int PACKET_BUFFER_SIZE;
    private static int SAMPLERATE;
    private static String MIME;
    private static String CODEC_NAME;
    private static int CHANNELCONFIG;
    private static int AUDIO_CHANNELS;
    private static int AUDIO_ENCODING_FORMAT;
    //Set audio buffer size to twice the minimum
    private static int AUDIO_BUFF_SIZE;
    private static int FETCH_TIMEOUT_US;
    private static int PUT_TIMEOUT_US;
    //How often to send a log report
    private static int LOGFREQ;
    //How long to wait for RX to stop in milliseconds
    private static int THREAD_TIMEOUT;
    //Time to wait for actions in seconds
    private static int ACTION_WAIT_TIMEOUT;
    //How many packet sequence numbers to consider invalid due to repeated or delayed
    private static int  RTP_SEQUENCE_TRACK_RANGE;

    //RTP packets
    private static int MISSING_PACKET_MAX_REPEAT;
    private static int MAX_RTP_QUEUE_LENGTH;
    private static int MAX_PACKET_SIZE;
    private static final byte[] AMR_WB_FRAME_SIZES = new byte[]{18, 24, 33, 37, 41, 47, 51, 59, 61, 6, 6, 0, 0, 0, 1, 1};
    private static final int RTP_HEADER_SIZE = 12;
    private static final byte RTP_PAYLOAD_ID = 97;
    private static final byte[] RTP_HEADER_START = new byte[]{
            //For explanation see https://en.wikipedia.org/wiki/Real-time_Transport_Protocol#Packet_header
            (byte) 0x80, (byte) 0xe1,   //Version,P,X,CC,M,PT
    };
    private static final byte[] RTP_HEADER_END = new byte[]{
            0x09, 0x1f,                 //SSRC identifier
            0x54, (byte)0xcf
    };
    private static final byte RTP_TOC_HEADER = (byte) 0xF0;

    private static int AUDIO_AMR_FRAMES_QUEUE_LENGTH;
    private static int AUDIO_PCM_FRAMES_QUEUE_LENGTH;
    private static int RTP_FETCH_TIMOUT_MS;
    private static int AMR_QUEUE_TIMEOUT_MS;
    private static int PCM_QUEUE_TIMEOUT_MS;


    private static MediaFormat format;

    public enum Status {
        STOPPED, STOPPING, STARTING, WAITING, RUNNING, UNAVAILABLE
    }

    public enum Command {
        START, START_NO_UUID_SEND, START_RTSP, TEST, STOP
    }


    private static Context context;

    private static MediaPlayer mPlayer;

    private static ByteBuffer rtpPacket;
    private static BlockingQueue rtpQueue, amrQueue, pcmQueue;
    private static boolean queueFilled = false;

    private static boolean rxRun = false;

    private static boolean audioOn = false;

    private static boolean actionLock = false;

    private static int channel = -1;
    private static InetAddress multicastIpAddress, multicastUuidIpAddress;

    private static Status state = Status.STOPPED;

    public TranslationRX (Properties prop) {
        context = MainActivity.context;

        MULTICAST_PORT = Integer.parseInt(prop.getProperty("MULTICAST_PORT"));
        MUTLICAST_IP_BASE = prop.getProperty("MUTLICAST_IP_BASE");
        MULTICAST_TIMEOUT = Integer.parseInt(prop.getProperty("RX_MULTICAST_TIMEOUT"));
        MUTLICAST_UUID_OFFSET = Integer.parseInt(prop.getProperty("MUTLICAST_UUID_OFFSET"));
        MUTLICAST_UUID_MS = Integer.parseInt(prop.getProperty("RX_MUTLICAST_UUID_MS"));
        MUTLICAST_UUID_JITTER_MS = Integer.parseInt(prop.getProperty("RX_MUTLICAST_UUID_JITTER_MS"));
        RTSP_PORT = Integer.parseInt(prop.getProperty("RTSP_PORT"));
        MAX_MULTICAST_TIMEOUTS_BEFORE_KICK = Integer.parseInt(prop.getProperty("RX_MAX_MULTICAST_TIMEOUTS_BEFORE_KICK"));
        PACKET_BUFFER_SIZE = Integer.parseInt(prop.getProperty("RX_PACKET_BUFFER_SIZE"));
        SAMPLERATE = Integer.parseInt(prop.getProperty("SAMPLERATE"));
        MIME = prop.getProperty("MIME");
        CODEC_NAME = prop.getProperty("RX_CODEC_NAME");
        AUDIO_CHANNELS = Integer.parseInt(prop.getProperty("AUDIO_CHANNELS"));
        CHANNELCONFIG = Tools.getAudioFormatIntProp(prop.getProperty("RX_CHANNELCONFIG"));
        AUDIO_ENCODING_FORMAT = Tools.getAudioFormatIntProp(prop.getProperty("AUDIO_ENCODING_FORMAT"));
        AUDIO_BUFF_SIZE = AudioTrack.getMinBufferSize(SAMPLERATE, CHANNELCONFIG, AUDIO_ENCODING_FORMAT);
        FETCH_TIMEOUT_US = Integer.parseInt(prop.getProperty("RX_FETCH_TIMEOUT_US"));
        PUT_TIMEOUT_US = Integer.parseInt(prop.getProperty("RX_PUT_TIMEOUT_US"));
        LOGFREQ = Integer.parseInt(prop.getProperty("RX_LOGFREQ"));
        RTP_SEQUENCE_TRACK_RANGE = Integer.parseInt(prop.getProperty("RX_RTP_SEQUENCE_TRACK_RANGE"));
        THREAD_TIMEOUT = Integer.parseInt(prop.getProperty("RX_THREAD_TIMEOUT"));
        ACTION_WAIT_TIMEOUT = Integer.parseInt(prop.getProperty("RX_ACTION_WAIT_TIMEOUT"));
        MISSING_PACKET_MAX_REPEAT = Integer.parseInt(prop.getProperty("RX_MISSING_PACKET_MAX_REPEAT"));
        MAX_RTP_QUEUE_LENGTH = Integer.parseInt(prop.getProperty("RX_MAX_RTP_QUEUE_LENGTH"));
        MAX_PACKET_SIZE = Integer.parseInt(prop.getProperty("RX_MAX_PACKET_SIZE"));
        AUDIO_AMR_FRAMES_QUEUE_LENGTH = Integer.parseInt(prop.getProperty("RX_AUDIO_AMR_FRAMES_QUEUE_LENGTH"));
        AUDIO_PCM_FRAMES_QUEUE_LENGTH = Integer.parseInt(prop.getProperty("RX_AUDIO_PCM_FRAMES_QUEUE_LENGTH"));
        RTP_FETCH_TIMOUT_MS = Integer.parseInt(prop.getProperty("RX_RTP_FETCH_TIMOUT_MS"));
        AMR_QUEUE_TIMEOUT_MS = Integer.parseInt(prop.getProperty("RX_AMR_QUEUE_TIMEOUT_MS"));
        PCM_QUEUE_TIMEOUT_MS = Integer.parseInt(prop.getProperty("RX_PCM_QUEUE_TIMEOUT_MS"));

        rtpPacket = ByteBuffer.allocate(MAX_PACKET_SIZE);

    }


    //Threadsafe transfer of RTP buffer
    static private void putRtpPacket(byte[] newRtpPacket, int rtpPacketLength, boolean first) {
        ByteBuffer toQueue = ByteBuffer.allocate(rtpPacketLength);
        toQueue.put(newRtpPacket, 0, rtpPacketLength);
        //Offer packet but drop if queue full
        rtpQueue.offer(toQueue);
    }

    private static Thread rxThread = null;
    private static void newRxThread () {
        rxThread = new Thread() {
            @Override
            public void run() {
                byte[] packetBuff = new byte[PACKET_BUFFER_SIZE];
                int packetLength, payLoadType, tocId, rtpSequenceNumber, rtpSequenceNumberPrevious = 0;
                int inLogCounter = LOGFREQ;
                MulticastSocket sock;


                Log.d(TAG, "RX Thread started");
                state = Status.STARTING;

                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

                while (rxRun) {
                    if (Tools.isWifiOn()) {
                        try {
                            sock = new MulticastSocket(MULTICAST_PORT);
                            sock.joinGroup(multicastIpAddress);
                            sock.setSoTimeout(MULTICAST_TIMEOUT);

                            //Main RX loop
                            for (int timeOutCount = 0; (timeOutCount < MAX_MULTICAST_TIMEOUTS_BEFORE_KICK) && rxRun; ) {
                                try {
                                    DatagramPacket pack = new DatagramPacket(packetBuff, PACKET_BUFFER_SIZE);

                                    Tools.acquireWifiHighPerfLock();
                                    Tools.acquireMulticastLock();
                                    sock.receive(pack);
                                    packetLength = pack.getLength();

                                    if (--inLogCounter <= 0) {
                                        Log.d(TAG, LOGFREQ + "th Sample read packet bytes : " + packetLength);
                                        inLogCounter = LOGFREQ;
                                    }

                                    //Pick off information from RTP header
                                    payLoadType = packetBuff[1] & 0x7F;
                                    rtpSequenceNumber = (packetBuff[3] & 0xFF) | ((packetBuff[2] & 0xFF) << 8);
                                    //Log.d(TAG, "RTP Sequence number " + rtpSequenceNumber);
                                    tocId = packetBuff[RTP_HEADER_SIZE];


                                    //Does this look like an AMR RTP packet?
                                    if ((packetLength > RTP_HEADER_SIZE) && (payLoadType == RTP_PAYLOAD_ID) && ((tocId & (byte) 0xFF) == RTP_TOC_HEADER)) {
                                        state = Status.RUNNING;

                                        //Ignore late or repeated packets unless it is a sequence roll-over
                                        if ((rtpSequenceNumber <= rtpSequenceNumberPrevious) &&
                                                ((rtpSequenceNumberPrevious - rtpSequenceNumber) < RTP_SEQUENCE_TRACK_RANGE) &&
                                                !((rtpSequenceNumber < 0x8000) && (rtpSequenceNumberPrevious > 0x8000))) {
                                            continue;
                                        }
                                        if (rtpSequenceNumber != (rtpSequenceNumberPrevious + 1)) {
                                            Log.w(TAG, "RTP Sequence number : " + rtpSequenceNumber + ", previous : " + rtpSequenceNumberPrevious);
                                        }

                                        //Initial tests passed for AMR RTP packet
                                        if (audioOn) {
                                            //Repeat same packet for any missing packets
                                            for (int i = 0; (i < (rtpSequenceNumber - rtpSequenceNumberPrevious)) && (i < MISSING_PACKET_MAX_REPEAT); i++) {
                                                putRtpPacket(packetBuff, packetLength, i == 0);
                                            }
                                        }
                                        rtpSequenceNumberPrevious = rtpSequenceNumber;
                                    //Otherwise does this look like a UUID list, if so, are we in it?
                                    } else if ((packetLength % MainActivity.uuid.length()) == 0) {
                                        state = Status.UNAVAILABLE;
                                        for (int packetPtr =0; packetPtr < packetLength; packetPtr += MainActivity.uuid.length()) {
                                            String rxUUID = new String(packetBuff, packetPtr, MainActivity.uuid.length(), Charset.forName("US-ASCII"));
                                            if (rxUUID.equals(MainActivity.uuid)) {
                                                state=Status.WAITING;
                                                break;
                                            }
                                        }
                                    } else {
                                        state = Status.UNAVAILABLE;
                                    }
                                    timeOutCount = 0;
                                } catch (SocketTimeoutException e) {
                                    state = Status.WAITING;
                                    if (audioOn) {
                                        Log.w(TAG, "Timeout waiting for packet");
                                        timeOutCount++;
                                    } else {
                                        Log.d(TAG, "Wait");
                                    }
                                }
                            }
                            if (rxRun) {
                                Log.w(TAG, "Multicast reset after " + MAX_MULTICAST_TIMEOUTS_BEFORE_KICK + " timeouts");
                            } else {
                                Log.d(TAG, "RX Thread ending");
                            }
                            sock.close();
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
                Tools.releaseMulticastLock();
            }
        };
    }

    private static Thread uuidThread = null;
    private static void newUuidThread () {
        uuidThread = new Thread() {
            @Override
            public void run() {
                MulticastSocket uuidSock;

                Log.d(TAG, "Starting UUID thread");

                while (rxRun) {
                    if (Tools.isWifiOn()) {
                        String lastWifiInfo = Tools.showWifiInfo();
                        Log.i(TAG, "Initial : " + lastWifiInfo);
                        try {
                            uuidSock = new MulticastSocket(MULTICAST_PORT);
                            uuidSock.joinGroup(multicastUuidIpAddress);
                            uuidSock.setSoTimeout(MULTICAST_TIMEOUT);
                            DatagramPacket pack = new DatagramPacket(new String("RX" + MainActivity.uuid).getBytes(), MainActivity.uuid.length() + 2, multicastUuidIpAddress, MULTICAST_PORT);
                            Random generator = new Random();
                            //Main UUID send loop
                            while (rxRun) {
                                try {
                                    Tools.acquireWifiHighPerfLock();
                                    Tools.acquireMulticastLock();
                                    uuidSock.send(pack);
                                } catch (IOException e) {
                                    Log.e(TAG, "UUID packet failed with " + e.toString());
                                    try {
                                        Thread.sleep(10000);
                                    } catch (InterruptedException f) {
                                        Log.d(TAG, "Wait before trying more UUID");
                                    }
                                    break;
                                }
                                try {
                                    Thread.sleep(MUTLICAST_UUID_MS + generator.nextInt(MUTLICAST_UUID_JITTER_MS));
                                } catch (InterruptedException e) {
                                    Log.d(TAG, "UUID sleep interrupted");
                                }
                            }
                            uuidSock.close();
                        } catch (IOException e) {
                            Log.e(TAG, "IOException " + e.toString());
                        }
                    } else {
                        //Sit and wait for wifi to appear
                        Log.d(TAG, "RX UUID waiting for Wi-fi to reappear");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Wait for wifi sleep interrupted");
                        }
                    }
                }
                Tools.releaseMulticastLock();
                Log.d(TAG, "Ending UUID thread");
            }
        };
    }



    private static Thread rtpThread = null;
    private static void newRtpThread () {
        rtpThread = new Thread () {
            @Override
            public void run () {
                int tocPtr, payLoadptr, amrFrameLength, payloadLengthCheck;


                while (rxRun) {
                    //Fetch the next packet
                    if (getRtpPacket()) {
                        tocPtr = RTP_HEADER_SIZE + 1;
                        payloadLengthCheck = 0;
                        //Advance payload pointer to end of TOC and start of data
                        for (payLoadptr = RTP_HEADER_SIZE + 1;
                             ((rtpPacket.get(payLoadptr) & (byte) 0x80) == (byte) 0x80) && (payLoadptr < rtpPacket.position());
                             payLoadptr++) {
                            //Calculate AMR frame size to check packet matches calculated length from TOC
                            payloadLengthCheck += rtpFrameSize (rtpPacket.get(payLoadptr));
                        }
                        //Add header and TOC size to AMR data size to yield calculated packet size and advance payload pointer past final TOC entry
                        payloadLengthCheck += rtpFrameSize (rtpPacket.get(payLoadptr)) + RTP_HEADER_SIZE + 1;
                        payLoadptr++;

                        if (payloadLengthCheck == rtpPacket.position()) {


                            while ((((tocPtr == RTP_HEADER_SIZE + 1) && (rtpPacket.get(tocPtr - 1) == RTP_TOC_HEADER)) ||
                                    ((rtpPacket.get(tocPtr - 1) & (byte) 0x80) == (byte) 0x80)) &&
                                    (payLoadptr < rtpPacket.position()) && rxRun) {

                                int frameSize = rtpFrameSize(rtpPacket.get(tocPtr));

                                //Get buffer to read into
                               ByteBuffer inputByteBuffer = ByteBuffer.allocate(frameSize);
                                //Write AMR frame header (from TOC entry)
                                inputByteBuffer.put((byte) (rtpPacket.get(tocPtr) & 0x7F));
                                //Copy AMR data (note that frame size includes the TOC entry, so must be subtracted by 1 to read actual data)
                                for (int i = 0; i < frameSize - 1; i++) {
                                    byte amrByte = rtpPacket.get(payLoadptr++);
                                    inputByteBuffer.put(amrByte);
                                }
                                try {
                                    if (!amrQueue.offer(inputByteBuffer, AMR_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                                        Log.w(TAG, "Timeout submitting to AMR queue");
                                    }
                                }
                                catch (InterruptedException e) {
                                    Log.d(TAG, "Poll status interrupted");
                                }
                                //Next AMR frame
                                tocPtr++;
                            }
                        } else {
                            Log.w(TAG, "RTP packet length : " + rtpPacket.position() + " doesn't match calculated length : " + payloadLengthCheck);
                        }
                    } else {
                        //Sleep for a 10 milliseconds
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Poll status interrupted");
                        }
                    }
                }
            }
        };
    }
    static private boolean getRtpPacket() {
        if (rtpQueue.remainingCapacity() == 0) {
            queueFilled = true;
        }
        //Wait until queue is full before playing out
        if (!rtpQueue.isEmpty() && queueFilled) {
            try {
                ByteBuffer fromQueue = (ByteBuffer) rtpQueue.poll(RTP_FETCH_TIMOUT_MS, TimeUnit.MILLISECONDS);
                if (fromQueue == null) {
                    Log.w(TAG, "Timeout waiting for RTP packet to process");
                } else {
                    rtpPacket.clear();
                    fromQueue.flip();
                    rtpPacket.put(fromQueue);
                    return true;
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "Queue remove interrupted");
            }
        }
        return false;
    }


    private static Thread codecThread = null;
    private static void newcodecThread () {
        codecThread = new Thread() {
            @Override
            public void run() {
                ByteBuffer amrByteBuffer = null;
                MediaCodec codec = null;
                ByteBuffer[] codecInputBuffers = null;
                ByteBuffer[] codecOutputBuffers = null;

                Log.d(TAG, "Codec Thread starting");
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                while (rxRun) {
                    ByteBuffer amrByteBufferPolled = (ByteBuffer) amrQueue.poll();
                    if (amrByteBufferPolled != null) {
                        amrByteBuffer = amrByteBufferPolled;
                    }

                    //Initialise or renew codec if required
                    if (codec == null) {
                        codec = getAudioCodec();
                        codecInputBuffers = codec.getInputBuffers();
                        codecOutputBuffers = codec.getOutputBuffers();
                    }

                    //Try to queue new AMR frame to the codec if available
                    if (amrByteBuffer != null) {
                        try {
                            int inputBufferId = codec.dequeueInputBuffer(PUT_TIMEOUT_US);

                            if (inputBufferId >= 0) {
                                int frameSize = amrByteBuffer.position();
                                ByteBuffer inputBuffer = codecInputBuffers[inputBufferId];

                                amrByteBuffer.flip();
                                inputBuffer.put(amrByteBuffer);
                                codec.queueInputBuffer(inputBufferId, 0, frameSize, 0, 0);
                                amrByteBuffer = null;
                            }
                        } catch (IllegalStateException e) {
                            Log.w(TAG, "Caught an IllegalStateException on codec input : " + e.toString());
                            killAudioCodec(codec);
                            codec = null;
                            continue;
                        }
                    }

                    try {
                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outputBufferId = codec.dequeueOutputBuffer(bufferInfo, FETCH_TIMEOUT_US);
                        switch (outputBufferId) {
                            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                                Log.d(TAG, "Codec output buffers changed");
                                codecOutputBuffers = codec.getOutputBuffers();
                                break;
                            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                Log.d(TAG, "Codec output format changed");
                                Log.d(TAG, "Assuming it will stay at " + SAMPLERATE + " Hz");
                                break;
                            case MediaCodec.INFO_TRY_AGAIN_LATER:
                                //Time-out try later
                                break;
                            default:
                                if (bufferInfo.size > 0) {
                                    int pcmBuffLen = bufferInfo.size;
                                    ByteBuffer outputBuffer = codecOutputBuffers[outputBufferId];
                                    ByteBuffer pcmBuff = ByteBuffer.allocate(pcmBuffLen);
                                    pcmBuff.put(outputBuffer);
                                    try {
                                        if (! pcmQueue.offer(pcmBuff, PCM_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS) ) {
                                            Log.w(TAG, "Timeout waiting to push to PCM queue");
                                        }
                                    } catch (InterruptedException e) {
                                        Log.d(TAG, "Queue poll interrupted");
                                    }

                                    outputBuffer.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME BUFFER BAD THINGS WILL HAPPEN
                                }
                                codec.releaseOutputBuffer(outputBufferId, false);
                        }
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "Caught an IllegalStateException on codec output : " + e.toString());
                        killAudioCodec(codec);
                        codec = null;
                    }
                }
                Log.d(TAG, "Codec Thread ending");
                killAudioCodec(codec);
            }
        };
    }
    static void killAudioCodec (MediaCodec codec) {
        if (codec != null) {
            try {
                codec.stop();
            }
            catch (IllegalStateException e) {
                Log.w(TAG, "Caught an IllegalStateException on codec stop");
            }
            codec.release();
        }
    }
    static MediaCodec getAudioCodec () {
        MediaCodec codec;

        Log.i(TAG, "Preparing audio codec");

        try {
            codec = MediaCodec.createByCodecName(CODEC_NAME);
        }
        catch (IOException e) {
            throw new IllegalStateException("IOException " + e.toString());
        }

        format = MediaFormat.createAudioFormat(MIME, SAMPLERATE, AUDIO_CHANNELS);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFF_SIZE);

        Log.d(TAG, "Configuring audio codec");
        codec.configure(format, null, null, 0);

        Log.d(TAG, "Starting audio codec");
        codec.start();
        Log.d(TAG, "Audio codec started");

        return codec;
    }


    private static Thread playOutThread = null;
    private static void newPlayOutThread () {
        playOutThread = new Thread () {
            @Override
            public void run() {
                int outLogCounter = LOGFREQ;
                AudioTrack audioTrack = null;


                if (pcmQueue == null) {
                    Log.e(TAG, "Playout thread can't start until pcmQueue is initialised");
                    return;
                }

                Log.d(TAG, "Playout Thread started");
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

                while(rxRun) {
                    ByteBuffer pcmByteBuffer;
                    byte[] pcmBuff = new byte[AUDIO_BUFF_SIZE];
                    try {
                        pcmByteBuffer = (ByteBuffer) pcmQueue.poll(PCM_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Queue poll interrupted");
                        break;
                    }
                    if (pcmByteBuffer != null) {
                        int pcmBuffLen = pcmByteBuffer.position();

                        pcmByteBuffer.flip();
                        pcmByteBuffer.get(pcmBuff, 0, pcmBuffLen);

                        if (audioTrack == null) {
                            audioTrack = getAudioTrack();
                        }
                        audioTrack.write(pcmBuff, 0, pcmBuffLen);

                        if (--outLogCounter <= 0) {
                            Log.d(TAG, LOGFREQ + "th Sample PCM size : " + pcmBuffLen);
                            outLogCounter = LOGFREQ;
                        }
                    }
                }
                Log.d(TAG, "Playout Thread ending");
                killAudioTrack(audioTrack);
            }
        };
    }
    static void killAudioTrack (AudioTrack audioTrack) {
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
        }
        Tools.releaseWifiHighPerfLock();
    }
    static AudioTrack getAudioTrack () {
        AudioManager audioManager;

        Log.i(TAG, "Setting up call mode");
        AudioTrack audioTrack;
        audioManager = (AudioManager) context.getSystemService(AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_CALL);

        Tools.phones_mode_set();

        Log.i(TAG, "Setting up audio track");
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL,
                SAMPLERATE, CHANNELCONFIG,
                AUDIO_ENCODING_FORMAT, AUDIO_BUFF_SIZE,
                AudioTrack.MODE_STREAM);

        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            if (audioTrack == null) {
                Log.e(TAG, "Audiotrack is null");
            }
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "Audiotrack is not initialized successfully");
            }
            throw new IllegalStateException("Could not create AudioTrack");
        }
        audioTrack.play();

        return audioTrack;
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

    private static int rtpFrameSize (byte key) {
        int frameSizeIndex = (key >> 3) & 0x0F;
        int frameSize = AMR_WB_FRAME_SIZES[frameSizeIndex];
        return frameSize;
    }

    private static String threadStateReport () {
        return (rxThread.getState() + ", " +
                ((uuidThread == null)?"-":uuidThread.getState()) + ", " +
                rtpThread.getState() + ", " +
                codecThread.getState() + ", " +
                playOutThread.getState());
    }
    private static void rxStart (boolean sendUuid) {
        if (channel >= 0) {
            if ((rxThread == null) || (rxThread.getState() == Thread.State.TERMINATED)) {
                newRxThread();
            }
            if (sendUuid && ((uuidThread == null) || (uuidThread.getState() == Thread.State.TERMINATED))) {
                newUuidThread();
            }
            if ((rtpThread == null) || (rtpThread.getState() == Thread.State.TERMINATED)) {
                newRtpThread();
            }
            if ((codecThread == null) || (codecThread.getState() == Thread.State.TERMINATED)) {
                newcodecThread();
            }
            if ((playOutThread == null) || (playOutThread.getState() == Thread.State.TERMINATED)) {
                newPlayOutThread();
            }
            if ((rxThread.getState() == Thread.State.NEW) &&
                    (!sendUuid || (uuidThread.getState() == Thread.State.NEW)) &&
                    (rtpThread.getState() == Thread.State.NEW) &&
                    (codecThread.getState() == Thread.State.NEW) &&
                    (playOutThread.getState() == Thread.State.NEW)) {
                setChannelIp();
                Log.d(TAG, "Starting RX, RTP, codec and playout Thread from state: " + threadStateReport());
                rxRun = true;

                rtpQueue = new ArrayBlockingQueue(MAX_RTP_QUEUE_LENGTH);

                rxThread.start();
                if (sendUuid) {
                    uuidThread.start();
                }
                if (audioOn) {
                    amrQueue = new ArrayBlockingQueue(AUDIO_AMR_FRAMES_QUEUE_LENGTH);
                    pcmQueue = new ArrayBlockingQueue(AUDIO_PCM_FRAMES_QUEUE_LENGTH);
                    rtpThread.start();
                    codecThread.start();
                    playOutThread.start();
                }
            } else {
                Log.e(TAG, "At least one thread is not runnable, RX, UUID, RTP, codec and playout state : " + threadStateReport());
            }
        } else {
            Log.i(TAG, "Channel has not yet been set so not starting RX and playout threads");
        }
    }
    private static void rxStop () {
        if (rxRun) {
            rxRun = false;
            if (playOutThread != null) {
                final Thread.State playOutThreadState = playOutThread.getState();
                Log.d(TAG, "Stopping Playout Thread from state : " + playOutThreadState);
                try {
                    playOutThread.join(THREAD_TIMEOUT);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Playout thread termination failed");
                }
                Log.d(TAG, "Stopped Playout thread");
            } else {
                Log.d(TAG, "Playout thread not yet defined so not stopping");
            }
            if (rtpThread != null) {
                final Thread.State rtpThreadState = rtpThread.getState();
                Log.d(TAG, "Stopping RTP Thread from state : " + rtpThreadState);
                try {
                    rtpThread.join(THREAD_TIMEOUT);
                } catch (InterruptedException e) {
                    Log.w(TAG, "RTP thread termination failed");
                }
                Log.d(TAG, "Stopped RTP thread");
            } else {
                Log.d(TAG, "RTP thread not yet defined so not stopping");
            }
            if (codecThread != null) {
                final Thread.State codecThreadState = codecThread.getState();
                Log.d(TAG, "Stopping codec Thread from state : " + codecThreadState);
                try {
                    codecThread.join(THREAD_TIMEOUT);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Codec thread termination failed");
                }
                Log.d(TAG, "Stopped codec thread");
            } else {
                Log.d(TAG, "Codec thread not yet defined so not stopping");
            }
            if (rxThread != null) {
                final Thread.State rxThreadState = rxThread.getState();
                Log.d(TAG, "Stopping RX thread from state : " + rxThreadState);
                try {
                    rxThread.join(THREAD_TIMEOUT);
                } catch (InterruptedException e) {
                    Log.w(TAG, "rx thread termination failed");
                }
                Log.d(TAG, "Stopped RX thread");
            } else {
                Log.d(TAG, "rx thread not yet defined so not stopping");
            }
            stopUuid();
        }
    }
    private static void stopUuid() {
        if (uuidThread != null) {
            final Thread.State uuidThreadState = uuidThread.getState();
            Log.d(TAG, "Stopping UUID thread from state : " + uuidThreadState);
            try {
                uuidThread.join(THREAD_TIMEOUT);
            } catch (InterruptedException e) {
                Log.w(TAG, "UUID thread termination failed");
            }
            Log.d(TAG, "Stopped UUID thread");
        } else {
            Log.d(TAG, "UUID thread not yet defined so not stopping");
        }
    }

    private static void rxRtspStart (boolean sendUuid) {
        String url = "rtsp://" + HubComms.getHostIp() + ":" + RTSP_PORT + "/" + String.format("%02d", channel);

        if (sendUuid) {
            if ((uuidThread == null) || (uuidThread.getState() == Thread.State.TERMINATED)) {
                newUuidThread();
            }
            if ((uuidThread.getState() == Thread.State.NEW)) {
                rxRun = true;
                setChannelIp();
                Log.d(TAG, "Starting UUID thread from state: " + uuidThread.getState());
                uuidThread.start();
            } else {
                Log.e(TAG, "UUID thread is not runnable");
            }
        }


        mPlayer = new MediaPlayer();
        try {
            mPlayer.setAudioStreamType(AudioManager.STREAM_VOICE_CALL);
            mPlayer.setDataSource(url);
            mPlayer.prepare();
            mPlayer.start();
        } catch (IOException e) {
            Log.e(TAG, "Unable to find stream : " + url);
        }

    }
    private static void rxRtspStop () {
        if (rxRun) {
            rxRun = false;
            stopUuid();
        }
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
    }
        
    public static synchronized void channelSelect(int setChannel) {
        channel = setChannel;
        Log.d(TAG, "Selecting Channel : " + (channel + 1));
    }

    public static synchronized int getChannel() {
        return channel;
    }

    public static synchronized String getRxThreadStatus() {
        Thread.State playOutThreadState, rxThreadState;
        String response = "";

        response += "rxTrhread : ";
        if (rxThread != null) {
            rxThreadState = rxThread.getState();
            response += rxThreadState;
        } else {
            response += "NULL";
        }

        response += ", playOutThread : ";
        if (playOutThread != null) {
            playOutThreadState = playOutThread.getState();
            response +=  playOutThreadState;
        } else {
            response += "NULL";
        }
        return response;
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

    // TODO implement RTSP  mode
    public static void action(Command action, boolean multicastMode) {
        boolean sendUuid = false;
        
        actionWait();
        setActionLock(true);
        switch(action) {
            case START:
                //Intentional drop through
                sendUuid = true;
            case START_NO_UUID_SEND:
                if (state != Status.STOPPED) {
                    rxStop();
                }
                Log.d(TAG, "Starting RX with audio");
                audioOn = true;
                if (multicastMode || !sendUuid) {
                    rxStart(sendUuid);
                } else {
                        rxRtspStart(sendUuid);
                }
                break;
            case STOP:
                //Signal stop in progress
                state = Status.STOPPING;
                rxStop();
                rxRtspStop();
                state = Status.STOPPED;
                break;
            case TEST:
                if (state != Status.STOPPED) {
                    rxStop();
                }
                Log.d(TAG, "Starting RX with no audio for channel sampling test");
                audioOn = false;
                rxStart(false);
                break;
        }
        setActionLock(false);
    }

    public static synchronized Status state() {
        return state;
    }
}
