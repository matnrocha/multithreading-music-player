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
    private final Lock
            lockPaused = new ReentrantLock(),
            lockStopped = new ReentrantLock();

    private final Condition
            songStopped = lockStopped.newCondition(),
            songPaused = lockPaused.newCondition();

    private SongState state;

    /** Each new track played is instantiated as a Thread object that uses this method call as parameter.
     *  When a new track thread starts, it will wait for the previous track to stop and signal the condition.
     */

     private enum SongState {
        PLAYING,
        PAUSED,
        STOPPED;
    }
    private void trackThread() {
        state = SongState.PLAYING;
        setTrack();

        try {
            while (state != SongState.STOPPED) {
                while (state == SongState.PAUSED) {
                    lockPaused.lock();
                    songPaused.await();             //thread waits to be unpaused
                    lockPaused.unlock();
                }
                if(state == SongState.PAUSED || !playNextFrame()) {
                    songToStop();
                    break;
                }
            }

            lockStopped.lock();
            songStopped.signal();
            closeBitStream();
            lockStopped.unlock();

        } catch (JavaLayerException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Set device for new Track
     */
    private void setTrack() {
        try {
            createBitStream();
        } catch (FileNotFoundException | JavaLayerException e) {
            throw new RuntimeException(e);
        }
    }

    private void songToPlay() throws InterruptedException {

        switch(state) {
            case PLAYING:
                state = SongState.STOPPED;

                lockStopped.lock();
                songStopped.await();
                lockStopped.unlock();
                break;
            case PAUSED:
                EventQueue.invokeLater(() -> {
                    window.setPlayPauseButtonIcon(1);
                });

                state = SongState.STOPPED;

                lockPaused.lock();
                songPaused.signal();        //wakes trackThread
                lockPaused.unlock();

                lockStopped.lock();
                songStopped.await();
                lockStopped.unlock();
                break;
            case STOPPED:
                //
                break;
        }



        new Thread(this::trackThread).start();

    }

    private void songPlayPause() {
        switch(state) {
            case PLAYING:
                state = SongState.PAUSED;
                EventQueue.invokeLater(() -> {
                    window.setPlayPauseButtonIcon(0);
                });
                break;
            case PAUSED:
                EventQueue.invokeLater(() -> {
                    window.setPlayPauseButtonIcon(1);
                });

                lockPaused.lock();
                songPaused.signal();        //wakes trackThread
                lockPaused.unlock();

                state = SongState.PLAYING;
                break;
            case STOPPED:
                break;
        }
    }

    private void songToStop() {

        switch(state) {
            case PLAYING:
                EventQueue.invokeLater(() -> window.resetMiniPlayer());
                break;
            case PAUSED:
                //
                break;
            case STOPPED:
                //
                break;
        }

        state = SongState.STOPPED;
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


    private final ActionListener buttonListenerPlayNow = e -> new Thread(() ->{
        int index = window.getSelectedSongIndex();
        playlist.setCurrentIndex(index);
        currentSong = playlist.get(index);

        EventQueue.invokeLater(() -> {
            window.setEnabledStopButton(true);
            window.setEnabledPlayPauseButton(true);
            window.setPlayPauseButtonIcon(window.BUTTON_ICON_PAUSE);
        });

        try {
            songToPlay();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }

    }).start();
    private final ActionListener buttonListenerRemove = e -> new Thread(() -> {
        int index = window.getSelectedSongIndex();
        if (index == playlist.getCurrentIndex()) songToStop();
        playlist.remove(index);
        updateWindow();
    }).start();
    private final ActionListener buttonListenerAddSong = e -> new Thread(() -> {
        Song newSong = window.openFileChooser();
        playlist.add(newSong);
        updateWindow();
    }).start();
    private final ActionListener buttonListenerPlayPause = e -> new Thread(this::songPlayPause).start();
    private final ActionListener buttonListenerStop = e -> new Thread(this::songToStop).start();
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
        this.state = SongState.STOPPED;
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
