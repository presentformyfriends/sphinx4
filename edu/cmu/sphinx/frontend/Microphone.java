/**
 * [[[copyright]]]
 */

package edu.cmu.sphinx.frontend;

import edu.cmu.sphinx.util.SphinxProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Vector;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;


/**
 * A Microphone captures audio data from the system's underlying
 * audio input systems. Converts these audio data into Audio
 * objects. The Microphone should be run in a separate thread.
 * When the method <code>startRecording()</code> is called, it will
 * start capturing audio, and stops when <code>stopRecording()</code>
 * is called. An Utterance is created for all the audio captured
 * in between calls to <code>startRecording()</code> and
 * <code>stopRecording()</code>.
 * Calling <code>getAudio()</code> returns the captured audio
 * data as Audio objects. If the SphinxProperty: <pre>
 * edu.cmu.sphinx.frontend.keepAudioReference </pre>
 * is set to true, then the Audio objects returned will contain
 * a reference to the (entire) Utterance object.
 */
public class Microphone extends DataProcessor implements AudioSource, Runnable {

    /**
     * Parameters for audioFormat
     */
    private AudioFormat audioFormat;
    private float sampleRate = 8000f;
    private int sampleSizeInBits = 16;
    private int channels = 1;
    private boolean signed = true;
    private boolean bigEndian = true;

    /**
     * The audio capturing device.
     */
    private TargetDataLine audioLine = null;
    private Utterance currentUtterance = null;

    private int frameSizeInBytes;
    private volatile boolean recording = false;
    private volatile boolean closed = false;

    private boolean keepAudioReference = true;
    private boolean utteranceEndSent = false;
    private boolean utteranceStarted = false;


    /**
     * Constructs a Microphone with the given InputStream.
     *
     * @param name the name of this Microphone
     * @param context the context of this Microphone
     */
    public Microphone(String name, String context) {
        super(name, context);
	initSphinxProperties();
        audioFormat = new AudioFormat(sampleRate, sampleSizeInBits,
                                      channels, signed, bigEndian);
    }


    /**
     * Reads the parameters needed from the static SphinxProperties object.
     */
    private void initSphinxProperties() {
	SphinxProperties properties = getSphinxProperties();

        sampleRate = (float) properties.getInt
            (FrontEnd.PROP_SAMPLE_RATE, 8000);
        frameSizeInBytes = properties.getInt
	    (FrontEnd.PROP_BYTES_PER_AUDIO_FRAME, 4096);

        if (frameSizeInBytes % 2 == 1) {
            frameSizeInBytes++;
        }

        keepAudioReference = properties.getBoolean
            (FrontEnd.PROP_KEEP_AUDIO_REFERENCE, true);
    }


    private void printMessage(String message) {
        System.out.println("Microphone: " + message);
    }


    /**
     * Terminates this Microphone, effectively terminates this
     * thread of execution. Calling <code>startRecording()</code>
     * will not work after call this method.
     */
    public synchronized void terminate() {
        setClosed(true);
        notify();
    }


    /**
     * Implements the <code>run()</code> method of Runnable.
     * It waits for instructions to record audio. The method
     * <code>startRecording()</code> will cause it to start recording
     * from the system audio capturing device.
     * Once it starts recording,
     * it will keep recording until it receives instruction to stop
     * recording. The method <code>stopRecording()</code> will cause
     * it to stop recording.
     */
    public void run() {
        while (!getClosed()) {
            waitToRecord();
            if (!getClosed()) {
                record();
            }
        }
        printMessage("finished running");
    }


    /**
     * This thread waits until some other thread calls <code>record()</code>
     */
    private synchronized void waitToRecord() {
        synchronized(this) {
            while (!getClosed() && !getRecording()) {
                try {
                    printMessage("waiting to record");
                    wait();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            }
            printMessage("finished waiting");
        }
    }


    /**
     * Records audio, and cache them in the audio buffer.
     */
    private void record() {

        if (audioLine != null && audioLine.isOpen()) {

            printMessage("started recording");

            audioLine.start();

            while (getRecording() && !getClosed()) {
                // Read the next chunk of data from the TargetDataLine.
                byte[] data = new byte[frameSizeInBytes];
                int numBytesRead =  audioLine.read(data, 0, data.length);
                
                if (numBytesRead == frameSizeInBytes) {
                    currentUtterance.add(data);
                } else {
                    numBytesRead = (numBytesRead % 2 == 0) ?
                        numBytesRead + 2 : numBytesRead + 3;

                    byte[] shrinked = new byte[numBytesRead];
                    System.arraycopy(data, 0, shrinked, 0, numBytesRead);
                    currentUtterance.add(shrinked);
                }

                printMessage("recorded 1 frame (" + numBytesRead + ") bytes");
            }

            audioLine.stop();
            audioLine.close();

            printMessage("stopped recording");

        } else {
            printMessage("Unable to open line");
        }
    }


    /**
     * Opens the audio capturing device so that it will be ready
     * for capturing audio.
     *
     * @return true if the audio capturing device is opened successfully;
     *     false otherwise
     */
    private boolean open() {
        DataLine.Info info = new DataLine.Info
            (TargetDataLine.class, audioFormat);
        
        if (!AudioSystem.isLineSupported(info)) {
            printMessage(audioFormat + " not supported");
            return false;
        }

        // Obtain and open the line.
        try {
            audioLine = (TargetDataLine) AudioSystem.getLine(info);
            audioLine.open(audioFormat);
            return true;
        } catch (LineUnavailableException ex) {
            audioLine = null;
            printMessage("Line unavailable");
            return false;
        }        
    }


    /**
     * Starts recording audio
     *
     * @return true if the recording started successfully; false otherwise
     */
    public synchronized boolean startRecording() {
        reset();
        if (open()) {
            setRecording(true);
            notify();
            return true;
        } else {
            return false;
        }
    }


    /**
     * Stops recording audio.
     */
    public void stopRecording() {
        if (audioLine != null) {
            setRecording(false);
        }
    }


    /**
     * Resets the Microphone, effectively clearing the audio buffer.
     */
    public void reset() {
        currentUtterance = new Utterance(getContext());
        utteranceStarted = false;
        utteranceEndSent = false;
    }

    
    /**
     * Reads and returns the next Audio object from this
     * Microphone, return null if there is no more audio data.
     * All audio data captured in-between <code>startRecording()</code>
     * and <code>stopRecording()</code> is cached in an Utterance
     * object. Calling this method basically returns the next
     * chunk of audio data cached in this Utterance. If the
     * SphinxProperty <pre>
     * edu.cmu.sphinx.frontend.keepAudioReference </pre> is true,
     * then the return Audio object will contain a reference to
     * the original Utterance object.
     *
     * @return the next Audio or <code>null</code> if none is
     *     available
     *
     * @throws java.io.IOException
     */
    public Audio getAudio() throws IOException {

        getTimer().start();

        Audio output = null;

        if (!utteranceStarted) {                
            utteranceStarted = true;
            output = new Audio(Signal.UTTERANCE_START);
        } else {
            output = readNextFrame();
            if (output == null) {
                if (!utteranceEndSent) {
                    output = new Audio(Signal.UTTERANCE_END);
                    utteranceEndSent = true;
                }
            }
        }

        getTimer().stop();

        return output;
    }


    /**
     * Returns the next Audio from AudioBuffer, or null if
     * there is none available.
     *
     * @return an Audio object or null
     *
     * @throws java.io.IOException
     */
    private Audio readNextFrame() throws IOException {

        // read one frame's worth of bytes
        byte[] audioFrame = null;

        do {
            audioFrame = currentUtterance.getNext();
        } while (audioFrame == null && getRecording());

        if (audioFrame == null) {
            return null;
        }

        // turn it into an Audio object
        Audio audio = null;
        
        if (keepAudioReference) {
            audio = new Audio
                (Util.byteToDoubleArray(audioFrame, 0, audioFrame.length),
                 currentUtterance);
        } else {
            audio = new Audio
                (Util.byteToDoubleArray(audioFrame, 0, audioFrame.length));
        }

        if (getDump()) {
            System.out.println("FRAME_SOURCE " + audio.toString());
        }
        
        return audio;
    }


    /**
     * Returns true if this Microphone is currently in a recording state;
     *    false otherwise
     *
     * @return true if recording, false if not recording
     */ 
    private synchronized boolean getRecording() {
        return recording;
    }

    
    /**
     * Sets whether this Microphone is in a recording state.
     *
     * @param recording true to set this Microphone in a recording state
     *     false to a non-recording state
     */
    private synchronized void setRecording(boolean recording) {
        this.recording = recording;
    }


    /**
     * Returns true if this Microphone thread finished running.
     * Normally, this Microphone is run in its own thread. If this
     * method returns true, it means the <code>run()</code> method
     * of the thread is finished running.
     *
     * @return true if this Microphone thread has finished running
     */
    private synchronized boolean getClosed() {
        return closed;
    }


    /**
     * Sets whether to terminate the Microphone thread.
     *
     * @param closed true to terminate the Micrphone thread
     */
    private synchronized void setClosed(boolean closed) {
        this.closed = closed;
    }
}
