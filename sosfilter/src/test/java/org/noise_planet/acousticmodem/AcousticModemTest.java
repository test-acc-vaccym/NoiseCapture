package org.noise_planet.acousticmodem;

import org.junit.Test;
import org.orbisgis.sos.*;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 *
 */
public class AcousticModemTest {

    private static final int[] UT_FREQUENCIES = new int[]{
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[15],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[16],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[17],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[18],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[19],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[20],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[21],
            (int)ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED[22]};


    public static final double REF_SOUND_PRESSURE = 1 / Math.pow(10, FFTSignalProcessing.DB_FS_REFERENCE / 20);

    @Test
    public void testEncodeDecodeWord() {
        String messageInput = "U1_76.8";

        // encode
        List<Integer[]> words = new ArrayList<>();
        for(byte data : messageInput.getBytes()) {
            int[] wordsRet = AcousticModem.byteToWordsIndex(data);
            words.add(new Integer[]{wordsRet[0], wordsRet[1]});
        }

        // decode
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        for(Integer[] wordTuple : words) {
            byteArrayOutputStream.write(AcousticModem.wordsToByte(wordTuple[0], wordTuple[1]));
        }

        assertEquals(messageInput, byteArrayOutputStream.toString());
    }


    @Test
    public void TestEncodeDecode() throws Exception {
        String messageInput = "U1_76.8";
        int sampleRate = 44100;

        // Convert data into audio signal
        int freqStart = Arrays.binarySearch(ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED, UT_FREQUENCIES[0]);
        AcousticModem acousticModem = new AcousticModem(new Settings(44100, 0.300, Settings.wordsFrom8frequencies(UT_FREQUENCIES)));
        byte[] data = acousticModem.encode(messageInput.getBytes());
        int offset = (int)(sampleRate * 0.412);
        int signalLength = acousticModem.getSignalLength(data, 0, data.length) + offset;
        short[] signal = new short[signalLength];
        acousticModem.wordsToSignal(data, 0, data.length, signal, offset, (short) 2500);

        // Convert audio signal back to data

        Window window = new Window(Window.WINDOW_TYPE.RECTANGULAR, 44100, ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED, 0.125, false, REF_SOUND_PRESSURE, false);

        int idSampleStart = 0;
        int packetSize = 1024;
        int lastPushIndex = 0;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        while (idSampleStart < signal.length) {
            // Compute sub-sample size in order to not skip samples
            int sampleLen = Math.min(window.getMaximalBufferSize(), packetSize);
            short[] samples = Arrays.copyOfRange(signal, idSampleStart, Math.min(signal.length, idSampleStart + sampleLen));
            idSampleStart += samples.length;

            window.pushSample(samples);
            if (window.getWindowIndex() != lastPushIndex) {
                lastPushIndex = window.getWindowIndex();
                FFTSignalProcessing.ProcessingResult res = window.getLastWindowMean();
                Byte words = acousticModem.spectrumToWord(Arrays.copyOfRange(res.getdBaLevels(), freqStart, freqStart + 8));
                if(words != null) {
                    byteArrayOutputStream.write(words);
                }
                window.cleanWindows();
            }
        }

        byte[] receivedBytes = byteArrayOutputStream.toByteArray();

        //writeToFile("out/signal2.raw", signal);

        assert(acousticModem.isMessageCheck(receivedBytes));
        assertEquals(messageInput, new String(acousticModem.decode(receivedBytes)));

    }

    private static void writeToFile(String path, short[] signal) throws IOException {
        FileOutputStream fileOutputStream = new FileOutputStream(path);
        try {
            ByteBuffer byteBuffer = ByteBuffer.allocate(Short.SIZE / Byte.SIZE);
            for(int i = 0; i < signal.length; i++) {
                byteBuffer.putShort(0, signal[i]);
                fileOutputStream.write(byteBuffer.array());
            }
        } finally {
            fileOutputStream.close();
        }
    }

    @Test
    public void testEncodeDecodeWithNoise() throws IOException {
        byte[] messageInput = makeMessage((short)1, 5, 4);
        int freqStart = 10;

        final int sampleRate = 44100;
        AcousticModem acousticModem = new AcousticModem(new Settings(44100, 0.200, Settings.wordsFrom8frequencies(UT_FREQUENCIES)));
        InputStream inputStream = AcousticModemTest.class.getResourceAsStream("signal_with_voice.raw");
        int windowSize = (int)(sampleRate * AcousticIndicators.TIMEPERIOD_FAST);
        FFTSignalProcessing fftSignalProcessing =
                new FFTSignalProcessing(sampleRate, ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED, windowSize);
        // Read input signal up to buffer.length
        short[] signal = SOSSignalProcessing.loadShortStream(inputStream, ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        for(int windowId = 0; windowId * windowSize < signal.length; windowId++) {
            fftSignalProcessing.addSample(Arrays.copyOfRange(signal, windowId * windowSize, Math.min(signal.length, windowId * windowSize + windowSize)));
            FFTSignalProcessing.ProcessingResult result = fftSignalProcessing.processSample(true, false, false);
            Byte res = acousticModem.spectrumToWord(acousticModem.filterSpectrum(Arrays.copyOfRange(result.getdBaLevels(), freqStart, freqStart + 8)));
            if(res != null) {
                byteArrayOutputStream.write(res);
            }
        }

        byte[] receivedBytes = byteArrayOutputStream.toByteArray();

        assertArrayEquals(messageInput, acousticModem.decode(receivedBytes));

        assertTrue(acousticModem.isMessageCheck(receivedBytes));
    }


    private static byte[] makeMessage(short id, float... data) {
        ByteBuffer byteBuffer = ByteBuffer.allocate((Short.SIZE + Float.SIZE * data.length) /
                Byte.SIZE);
        byteBuffer.putShort(id);
        for(int i=0; i<data.length; i++) {
            byteBuffer.putFloat(data[i]);
        }
        return byteBuffer.array();
    }

    @Test
    public void testFrequencyFilter() throws IOException {
        int freqStart = Arrays.binarySearch(ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED, UT_FREQUENCIES[0]);
        final int sampleRate = 44100;
        AcousticModem acousticModem = new AcousticModem(new Settings(44100, 0.200, Settings.wordsFrom8frequencies(UT_FREQUENCIES)));

        InputStream inputStream = AcousticModemTest.class.getResourceAsStream("signal_with_voice.raw");
        int windowSize = 1024;
        FFTSignalProcessing fftSignalProcessing =
                new FFTSignalProcessing(sampleRate, ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED, windowSize);
        // Read input signal up to buffer.length
        short[] signal = SOSSignalProcessing.loadShortStream(inputStream, ByteOrder.LITTLE_ENDIAN);
        System.out.println(Arrays.toString(acousticModem.getSettings().frequencies));
        for(int windowId = 0; windowId * windowSize < signal.length; windowId++) {
            fftSignalProcessing.addSample(Arrays.copyOfRange(signal, windowId * windowSize, Math.min(signal.length, windowId * windowSize + windowSize)));
            FFTSignalProcessing.ProcessingResult result = fftSignalProcessing.processSample(true, false, false);
            float[] filteredSpectrum = acousticModem.filterSpectrum(Arrays.copyOfRange(result.getdBaLevels(), freqStart, freqStart + 8));
            System.out.println(Arrays.toString(filteredSpectrum));
        }
    }





    @Test
    public void testEncodeDecodeWithConferenceNoise() throws IOException {
        int freqStart = 10;

        final int sampleRate = 44100;
        AcousticModem acousticModem = new AcousticModem(new Settings(44100, 0.200, Settings.wordsFrom8frequencies(UT_FREQUENCIES)));
        InputStream inputStream = AcousticModemTest.class.getResourceAsStream("signal_anqes2017" +
                ".raw");
        int windowSize = (int)(sampleRate * AcousticIndicators.TIMEPERIOD_FAST);
        FFTSignalProcessing fftSignalProcessing =
                new FFTSignalProcessing(sampleRate, ThirdOctaveBandsFiltering.STANDARD_FREQUENCIES_REDUCED, windowSize);
        // Read input signal up to buffer.length
        short[] signal = SOSSignalProcessing.loadShortStream(inputStream, ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        for(int windowId = 0; windowId * windowSize < signal.length; windowId++) {
            fftSignalProcessing.addSample(Arrays.copyOfRange(signal, windowId * windowSize, Math.min(signal.length, windowId * windowSize + windowSize)));
            FFTSignalProcessing.ProcessingResult result = fftSignalProcessing.processSample(true, false, false);
            Byte res = acousticModem.spectrumToWord(acousticModem.filterSpectrum(Arrays.copyOfRange(result.getdBaLevels(), freqStart, freqStart + 8)));
            if(res != null) {
                byteArrayOutputStream.write(res);
            }
        }

        byte[] receivedBytes = byteArrayOutputStream.toByteArray();


        assertTrue(acousticModem.isMessageCheck(receivedBytes));

        Message message = new Message(acousticModem.decode(receivedBytes));

        assertEquals(1, message.id);
        assertEquals(10, message.args.get(0), 0);
        assertEquals(10, message.args.get(1), 0);

    }


    private static class Message {
        final short id;
        final List<Float> args = new ArrayList<>();

        public Message(byte... data) {
            ByteBuffer byteBuffer = ByteBuffer.wrap(data);
            id = byteBuffer.getShort();
            while(byteBuffer.hasRemaining()) {
                args.add(byteBuffer.getFloat());
            }
        }
    }
}