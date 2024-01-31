import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Playlist;
import support.Song;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;
    private Playlist playlist;
    private int currentFrame = 0;
    private Song currentSong;
    private boolean playing;
    private boolean paused;
    private final Lock lock = new ReentrantLock();
    private final Condition songStopped = lock.newCondition();
    private final Condition songPaused = lock.newCondition();

    /** Each new track played is instantiated as a Thread object that uses this method call as parameter.
     *  When a new track thread starts, it will wait for the previous track to stop and signal the condition.
     */
    private void trackThread() {
        playing = true;
        paused = false;
        boolean EOF = false;
        if (decoder != null) {
            closeBitStream();
        }
        try {
            createBitStream();
            while (playing && !EOF) {
                if(paused) {
                    lock.lock();
                    songPaused.await();             //thread waits to be unpaused
                    lock.unlock();
                }
                EOF = !playNextFrame();
            }

            lock.lock();
            songStopped.signal();
            lock.unlock();

            stopSong();

        } catch (FileNotFoundException | JavaLayerException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    /** Closes the bit stream and the audio device */
    private void closeBitStream(){
        try{
            bitstream.close();
        } catch (BitstreamException e) {
            throw new RuntimeException(e);
        }
        device.close();
    }

    /**Create a new audio device as well as a new input stream
     *
     * @throws JavaLayerException
     * @throws FileNotFoundException
     */
    private void createBitStream() throws JavaLayerException, FileNotFoundException {
        device = FactoryRegistry.systemRegistry().createAudioDevice();
        device.open(decoder = new Decoder());
        bitstream = new Bitstream(currentSong.getBufferedInputStream());
    }

    /** Stop any song from playing and resets the GUI
     *
     */
    private void stopSong() {
        playing = false;
        EventQueue.invokeLater(() -> window.resetMiniPlayer());
    }

    private final ActionListener buttonListenerPlayNow = e -> new Thread(() ->{
        int index = window.getSelectedSongIndex();
        playlist.setCurrentIndex(index);
        currentSong = playlist.get(index);
        EventQueue.invokeLater(() -> {
            window.setEnabledStopButton(true);
            window.setEnabledPlayPauseButton(true);
            window.setPlayPauseButtonIcon(window.BUTTON_ICON_PAUSE);
        });
        lock.lock();
        try {
            if (playing) {
                playing = false;       // Signals to the previous thread to stop reproduction;
                songStopped.await();   // Then waits for it to stop;
            }
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        } finally {
            lock.unlock();
        }
        new Thread(this::trackThread).start();

    }).start();
    private final ActionListener buttonListenerRemove = e -> new Thread(() -> {
        int index = window.getSelectedSongIndex();
        if (index == playlist.getCurrentIndex()) stopSong();
        playlist.remove(index);
        updateWindow();
    }).start();
    private final ActionListener buttonListenerAddSong = e -> new Thread(() -> {
        Song newSong = window.openFileChooser();
        playlist.add(newSong);
        updateWindow();
    }).start();
    private final ActionListener buttonListenerPlayPause = e -> new Thread(() -> {
        paused = !paused;

        if(paused) {
            EventQueue.invokeLater(() -> {
                window.setPlayPauseButtonIcon(0);
            });
        } else {
            EventQueue.invokeLater(() -> {
                window.setPlayPauseButtonIcon(1);
            });

            lock.lock();
            songPaused.signal();        //wakes trackThread
            lock.unlock();
        }
    }).start();
    private final ActionListener buttonListenerStop = e -> new Thread(this::stopSong).start();
    private final ActionListener buttonListenerNext = e -> {};
    private final ActionListener buttonListenerPrevious = e -> {};
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };

    private void updateWindow() {
        EventQueue.invokeLater(() -> window.setQueueList(playlist.getDisplayInfo()));
    }

    public Player() {
        this.playlist = new Playlist();

        try {
            this.device = FactoryRegistry.systemRegistry().createAudioDevice();
        } catch (JavaLayerException e) {
            throw new RuntimeException(e);
        }

        String[][] table = playlist.getDisplayInfo();
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "Player", // Placeholder title
                table,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">
    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        int framesToSkip = newFrame - currentFrame;
        boolean condition = true;
        while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
    }
    //</editor-fold>
}
