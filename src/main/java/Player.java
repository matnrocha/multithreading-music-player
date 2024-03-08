import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import jdk.jfr.Event;
import support.PlayerWindow;
import support.Playlist;
import support.Song;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.util.Objects;
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
    private int currentFrame;
    private float scrubberValue;
    private Song currentSong;

    private final Lock
            lockPaused = new ReentrantLock(),
            trackTimeLock = new ReentrantLock(),
            lockPlaying = new ReentrantLock();

    private final Condition
            threadUnpaused = lockPaused.newCondition();

    private SongState state;

    private final Thread TrackThread = new Thread(this::PlayTrack);

     private enum SongState {
        PLAYING,
        PAUSED,
        STOPPED;
    }

    /** Each new track played is instantiated as a Thread object that uses this method call as parameter.
     *  When a new track thread starts, it will wait for the previous track to stop and signal the condition.
     */
    private void PlayTrack() {
        try {
            while (true) {
                while (state != SongState.PLAYING) {
                    lockPaused.lock();
                    threadUnpaused.await();             //thread waits to be unpaused
                    lockPaused.unlock();
                }

                lockPlaying.lock();
                boolean EOF = !playNextFrame();
                lockPlaying.unlock();

                if(EOF) {
                    if(playlist.hasNext()) {
                        playNext();
                    } else {
                        songToStop();
                    }
                } else currentFrame++;

                if(trackTimeLock.tryLock() && state != SongState.STOPPED)
                {
                    updateTrackTime();
                    trackTimeLock.unlock();
                }
            }

        } catch (JavaLayerException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * updates the info of the track
     */
    private void updateTrackInfo() {
        switch(state) {
            case PLAYING:
                EventQueue.invokeLater(() -> {
                    window.setPlayingSongInfo(
                        currentSong.getTitle(),
                        currentSong.getAlbum(),
                        currentSong.getArtist());
                });
                updateButtonsNextPrevious();

                break;
            case STOPPED:
                EventQueue.invokeLater(() -> {
                    window.setEnabledScrubber(false);
                    window.resetMiniPlayer();
                });
                break;
        }
    }

    private void updateSongPanels() {
        EventQueue.invokeLater(() -> window.setQueueList(playlist.getDisplayInfo()));
    }

    /**
     * Update buttons NextSong and PreviousSong
     */
    private void updateButtonsNextPrevious(){
        if(state != SongState.STOPPED){
            EventQueue.invokeLater(() -> {
                window.setEnabledNextButton(playlist.hasNext());
                window.setEnabledPreviousButton(playlist.hasPrevious());
            });
        }
    }

    /**
     * Update Stop and Play/Pause buttons when a song is started
     */
    private void updateCentralButtons() {
        EventQueue.invokeLater(() -> {
            window.setEnabledStopButton(true);
            window.setEnabledPlayPauseButton(true);
            window.setPlayPauseButtonIcon(window.BUTTON_ICON_PAUSE);
        });
    }

    private void updateTrackTime() {
        EventQueue.invokeLater(() -> { window.setTime((int)(currentFrame * currentSong.getMsPerFrame()), (int)currentSong.getMsLength());});
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
        currentFrame = 0;
    }



    private void songPlayNow(int songIndex) throws InterruptedException {
        if (state == SongState.STOPPED && !TrackThread.isAlive()) TrackThread.start();
        changeCurrentSong(songIndex);
        updateTrackInfo();

        lockPlaying.lock();
        if (bitstream != null) closeBitStream();
        setTrack();
        lockPlaying.unlock();

        if (state != SongState.PLAYING) songPlayPause();

        updateButtonsNextPrevious();
        updateCentralButtons();
        EventQueue.invokeLater(() -> window.setEnabledScrubber(true));
    }

    private void songPlayPause() {
        if (state == SongState.PLAYING) {
            state = SongState.PAUSED;
            EventQueue.invokeLater(() -> window.setPlayPauseButtonIcon(window.BUTTON_ICON_PLAY));
        } else {
            lockPaused.lock();
            state = SongState.PLAYING;
            threadUnpaused.signal();        //wakes trackThread
            lockPaused.unlock();
            EventQueue.invokeLater(() -> window.setPlayPauseButtonIcon(window.BUTTON_ICON_PAUSE));
        }
    }

    private void songToStop() {
        state = SongState.STOPPED;
        currentFrame = 0;
        updateTrackTime();
        updateTrackInfo();
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
     */
    private void createBitStream() throws JavaLayerException, FileNotFoundException {
        device = FactoryRegistry.systemRegistry().createAudioDevice();
        device.open(decoder = new Decoder());
        bitstream = new Bitstream(currentSong.getBufferedInputStream());
    }

    /**
     * @param index new index
     */
    private void changeCurrentSong(int index){
        playlist.setCurrentIndex(index);
        currentSong = playlist.get(index);
    }

    private void playNext(){
        try {
            songPlayNow(playlist.getNextIndex());
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void playPrevious() {
        try {
            songPlayNow(playlist.getPreviousIndex());
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }
    private final ActionListener buttonListenerPlayNow = e -> {
        try {
            songPlayNow(window.getSelectedSongIndex());
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    };
    private final ActionListener buttonListenerRemove = e -> new Thread(() -> {
        int index = window.getSelectedSongIndex();
        if(currentSong != null && Objects.equals(currentSong.getUuid(), window.getSelectedSongID())) {
            songToStop();
        }

        playlist.remove(index);
        updateSongPanels();
        updateButtonsNextPrevious();
    }).start();
    private final ActionListener buttonListenerAddSong = e -> new Thread(() -> {
        Song newSong = window.openFileChooser();
        if (newSong != null) playlist.add(newSong);
        updateSongPanels();
        updateButtonsNextPrevious();
    }).start();
    private final ActionListener buttonListenerPlayPause = e -> {
        songPlayPause();
    };
    private final ActionListener buttonListenerStop = e -> new Thread(this::songToStop).start();
    private final ActionListener buttonListenerNext = e -> new Thread(this::playNext).start();
    private final ActionListener buttonListenerPrevious = e -> new Thread(this::playPrevious).start();
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
            trackTimeLock.unlock();
            lockPlaying.lock();
            try {
                int frame = (int) (scrubberValue / currentSong.getMsPerFrame());
                closeBitStream();
                setTrack();
                skipToFrame(frame);
            } catch (JavaLayerException ex) {
                throw new RuntimeException(ex);
            } finally {
                lockPlaying.unlock();
            }
            trackTimeLock.lock();
            updateTrackTime();
            trackTimeLock.unlock();

        }

        @Override
        public void mousePressed(MouseEvent e) {
            trackTimeLock.lock();
            scrubberValue = window.getScrubberValue();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            scrubberValue = window.getScrubberValue();
            EventQueue.invokeLater(() -> window.setTime(
                    (int) scrubberValue,
                    (int) currentSong.getMsLength()));
        }
    };

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
