package fm.a2d.sf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import fm.a2d.sf.helper.L;
import fm.a2d.sf.helper.Utils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.Thread.State;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class RadioRecorder {
  private int mChannels;
  private com_api mApi;
  private boolean mNeedFinish = false;

  private RandomAccessFile mRandomAccessFile = null;
  private BufferedOutputStream mBufferOutStream = null;
  private FileOutputStream mFileOutStream = null;
  private File mRecordFile = null;

  @SuppressWarnings("UnusedAssignment")
  private int mSampleRate = 44100;

  private int mRecordDataSize = 0;
  private final int RECORD_BUFFER_SIZE = 1048576;
  private byte[] mRecordBufferData = new byte[RECORD_BUFFER_SIZE];
  private int mRecordBufferHead = 0;
  private int mRecordBufferTail = 0;

  private boolean mRecordWriteThreadActive = false;
  private Thread mRecordThread = null;

  private long mStartTime;

  private static void log(String s) {
    L.w(L.T.RECORDER, s);
  }

  private final Runnable mRecordWriteRunnable = new Runnable() {

    public void run() {
      log("mRecordWriteRunnable run()");
      int cur_buf_tail;

      if (mRecordBufferData == null) {
        mRecordBufferData = new byte[RECORD_BUFFER_SIZE];
      }

      while (mRecordWriteThreadActive) {
        try {
          cur_buf_tail = mRecordBufferTail;
          int len = 0;
          if (cur_buf_tail == mRecordBufferHead) {
            Thread.sleep(1000);
          } else {
            len = cur_buf_tail > mRecordBufferHead
                  ? cur_buf_tail - mRecordBufferHead
                : RECORD_BUFFER_SIZE - mRecordBufferHead;
            if (len == 0) {
              log("len = 0 mRecordWriteRunnable run() len: " + len + "  mRecordBufferHead: " + mRecordBufferHead + "  cur_buf_tail: " + cur_buf_tail + "  rec_buf_size: " + RECORD_BUFFER_SIZE);
            } else if (len < 0) {
              log("len < 0 mRecordWriteRunnable run() len: " + len + "  mRecordBufferHead: " + mRecordBufferHead + "  cur_buf_tail: " + cur_buf_tail + "  rec_buf_size: " + RECORD_BUFFER_SIZE);
            } else {
              log("len > 0 mRecordWriteRunnable run() len: " + len + "  mRecordBufferHead: " + mRecordBufferHead + "  cur_buf_tail: " + cur_buf_tail + "  rec_buf_size: " + RECORD_BUFFER_SIZE);
              if (mRandomAccessFile == null) {
                mRandomAccessFile = new RandomAccessFile(mRecordFile, "rw");
              }
              if (mBufferOutStream != null) {
                mBufferOutStream.write(mRecordBufferData, mRecordBufferHead, len);
                mRecordDataSize += len;
              }
              log("Wrote " + len + " bytes to record buffer head: " + mRecordBufferHead);
              mRecordBufferHead += len;
              if (mRecordBufferHead < 0 || mRecordBufferHead >= RECORD_BUFFER_SIZE) {
                mRecordBufferHead = 0;
              }
              log("new mRecordBufferHead: " + mRecordBufferHead);
            }
          }
        } catch (InterruptedException e) {
          log("mRecordWriteRunnable run() throwable InterruptedException");
        } catch (Throwable e2) {
          log("mRecordWriteRunnable run() throwable: " + e2);
          e2.printStackTrace();
        }
      }
      log("mRecordWriteRunnable run() done");
      if (mNeedFinish) {
        mNeedFinish = false;
        audio_record_finish();
      }
    }
  };

  private WeakReference<Context> mWContext;

  public RadioRecorder(Context context, int sampleRate, int channels, com_api api) {
    mSampleRate = sampleRate;
    mChannels = channels;
    mApi = api;
    mWContext = new WeakReference<>(context);
  }

  public boolean start() {
    if (!mApi.audio_record_state.equals(C.RECORD_STATE_STOP)) {
      return false;
    }

    log("Staring recording...");

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    sdf.setTimeZone(TimeZone.getDefault());
    String directory = "/Music/FM" + File.separator + sdf.format(new Date());

    mRecordFile = null;

    mStartTime = System.currentTimeMillis();

    File recordDirectory = new File(com_uti.getExternalStorageDirectory().getPath() + directory);

    if (!(recordDirectory.exists() || recordDirectory.mkdirs())) {
      log("start(): Directory " + recordDirectory + " mkdir fails");
    }

    if (!recordDirectory.canWrite()) {
      recordDirectory = com_uti.getExternalStorageDirectory();
    }
    if (!recordDirectory.canWrite()) {
      log("start(): cannot write");
      return false;
    }

    String filename = getFilename();
    try {
      mRecordFile = new File(recordDirectory, filename);
      log("start(): trying create in dir " + recordDirectory + " file " + filename);

      //noinspection ResultOfMethodCallIgnored
      mRecordFile.createNewFile();

      mFileOutStream = new FileOutputStream(mRecordFile, true);
      mBufferOutStream = new BufferedOutputStream(mFileOutStream, 131072);

      mRecordDataSize = 0;

      if (!writeWavHeader()) {
        audio_record_finish();
        return false;
      }

      if (!mRecordWriteThreadActive) {
        mRecordThread = new Thread(mRecordWriteRunnable, "rec_write");
        log("record thread: " + mRecordThread);

        if (mRecordThread == null) {
          log("record thread == null; finishing...");
          audio_record_finish();
          return false;
        }

        mRecordWriteThreadActive = true;

        try {
          if (mRecordThread.getState() == State.NEW || mRecordThread.getState() == State.TERMINATED) {
            mRecordThread.start();
          }
        } catch (Throwable e) {
          log("on start thread exception occurred: " + e);
          log("finishing...");
          e.printStackTrace();
          audio_record_finish();
          mApi.audio_record_state = C.RECORD_STATE_STOP;
          return false;
        }
      }

      mApi.audio_record_state = C.RECORD_STATE_START;
      mNeedFinish = true;
      return true;
    } catch (Throwable e1) {
      log("start(): unable to create file: " + e1);
      e1.printStackTrace();
      return false;
    }
  }

  public void write(byte[] buffer, int length) {
    if (!mRecordWriteThreadActive) {
      return;
    }

    if (mRecordDataSize < 0 && mRecordDataSize + length + 36 > -4) {
      log("write(): !!! Max mRecordDataSize: " + mRecordDataSize + "; length: " + length);
      mApi.audio_record_state = C.RECORD_STATE_STOP;
      mNeedFinish = true;
    }

    if (!mApi.audio_record_state.equals(C.RECORD_STATE_STOP)) {
      writeBuffer(buffer, length);
    }
  }

  public void setState(String state) {
    if (state.equals(C.RECORD_STATE_TOGGLE)) {
      if (mApi.audio_record_state.equals(C.RECORD_STATE_STOP)) {
        state = C.RECORD_STATE_START;
      } else {
        state = C.RECORD_STATE_STOP;
      }
    }

    if (state.equals(C.RECORD_STATE_STOP)) {
      stop();
    } else if (state.equals(C.RECORD_STATE_START)) {
      start();
    }
  }

  private void audio_record_finish() {
    try {
      if (mRecordDataSize != 0) {
        writeFinal();
      }
      mRecordDataSize = 0;
      if (mBufferOutStream != null) {
        mBufferOutStream.close();
        mBufferOutStream = null;
      }
      if (mFileOutStream != null) {
        mFileOutStream.close();
        mFileOutStream = null;
      }
    } catch (Throwable e) {
      log("throwable: " + e);
      e.printStackTrace();
    }
    mRandomAccessFile = null;
  }

  public void stop() {
    boolean active = mRecordWriteThreadActive;
    final int size = mRecordDataSize;
    log("stop(): record_state=" + mApi.audio_record_state);
    mRecordWriteThreadActive = false;
    if (mRecordThread != null) {
      mRecordThread.interrupt();
    }
    mApi.audio_record_state = C.RECORD_STATE_STOP;
    final Context c = mWContext.get();

    // TODO replace by receivers
    if (c != null && active) {
      new Handler(Looper.getMainLooper()).post(new Runnable() {
        @Override
        public void run() {
          String dur = Utils.getTimeStringBySeconds(getCurrentDuration());

          float sizeMB = size / 1024f / 1024f;

          Toast.makeText(c, String.format(Locale.ENGLISH, "Recorded %s - %.1f MB.", dur, sizeMB), Toast.LENGTH_LONG).show();
        }
      });
    }
  }

  private void writeBuffer(byte[] buffer, int length) {
    int have1_len;
    int have2_len;
    int cur_buf_head = mRecordBufferHead;

    if (mRecordBufferData == null || buffer == null || length <= 0 || buffer.length < length) {
      //noinspection ImplicitArrayToString
      log("writeBuffer(): !!! length=" + length + "; buffer=" + buffer + "; mRecordBufferData=" + mRecordBufferData);
      return;
    }

    if (cur_buf_head < 0 || cur_buf_head > RECORD_BUFFER_SIZE) {
      log("!!!  cur_buf_head: " + cur_buf_head);
      cur_buf_head = 0;
      mRecordBufferHead = 0;
    }

    if (mRecordBufferTail < 0 || mRecordBufferTail > RECORD_BUFFER_SIZE) {
      log("!!!  mRecordBufferTail: " + mRecordBufferTail);
      mRecordBufferTail = 0;
    }

    if (mRecordBufferTail == cur_buf_head) {
      have1_len = 0;
      have2_len = 0;
    } else if (mRecordBufferTail > cur_buf_head) {
      have1_len = mRecordBufferTail - cur_buf_head;
      have2_len = 0;
    } else {
      have1_len = RECORD_BUFFER_SIZE - cur_buf_head;
      have2_len = mRecordBufferTail;
    }

    if (have1_len < 0) {
      log("writeBuffer(): !!! Negative have length: " + length + "  have1_len: " + have1_len + "  have2_len: " + have2_len + "  cur_buf_head: " + cur_buf_head + "  mRecordBufferTail: " + mRecordBufferTail + "  rec_buf_size: " + RECORD_BUFFER_SIZE);
    } else if (have1_len + have2_len + length >= RECORD_BUFFER_SIZE) {
      com_uti.loge("!!! Attempt to exceed record buf length: " + length + "  have1_len: " + have1_len + "  have2_len: " + have2_len + "  cur_buf_head: " + cur_buf_head + "  mRecordBufferTail: " + mRecordBufferTail + "  rec_buf_size: " + RECORD_BUFFER_SIZE);

      if (mRecordThread != null) {
        mRecordThread.interrupt();
      }
    } else {
      int writ1_len;
      int writ2_len;

      if (mRecordBufferTail + length <= RECORD_BUFFER_SIZE) {
        writ1_len = length;
        writ2_len = 0;
      } else {
        writ1_len = RECORD_BUFFER_SIZE - mRecordBufferTail;
        writ2_len = length - writ1_len;
      }

      if (writ1_len > 0) {
        System.arraycopy(buffer, 0, mRecordBufferData, mRecordBufferTail, writ1_len);

        log("Wrote writ1_len: " + writ1_len + "  to mRecordBufferTail: " + mRecordBufferTail);

        mRecordBufferTail += writ1_len;
        if (mRecordBufferTail >= RECORD_BUFFER_SIZE || mRecordBufferTail < 0) {
          mRecordBufferTail = 0;
        }

        log("new mRecordBufferTail: " + mRecordBufferTail);
        if (writ2_len > 0) {
          System.arraycopy(buffer, writ1_len, mRecordBufferData, 0, writ2_len);
          mRecordBufferTail = writ2_len;

          log("Wrote writ2_len: " + writ2_len + "  to mRecordBufferTail 0 / BEGIN  new mRecordBufferTail: " + mRecordBufferTail);
        }
      }

      if (mRecordBufferTail >= RECORD_BUFFER_SIZE || mRecordBufferTail < 0) {
        mRecordBufferTail = 0;
      }

      log("final new mRecordBufferTail: " + mRecordBufferTail);
    }
  }

  private void writeBytes(byte[] buffer, int index, int bytes, int value) {
    if (bytes > 0) {
      buffer[index] = (byte) (value & 255);
    }
    if (bytes > 1) {
      buffer[index + 1] = (byte) ((value >> 8) & 255);
    }
    if (bytes > 2) {
      buffer[index + 2] = (byte) ((value >> 16) & 255);
    }
    if (bytes > 3) {
      buffer[index + 3] = (byte) ((value >> 24) & 255);
    }
  }

  private boolean writeWavHeader() {
    byte[] header = com_uti.stringToByteArray("RIFF....WAVEfmt sc1safncsamrbytrbabsdatasc2s");
    log("wavHeader.length: " + header.length);
    writeBytes(header, 4, 4, mRecordDataSize + 36);
    writeBytes(header, 16, 4, 16);
    writeBytes(header, 20, 2, 1);
    writeBytes(header, 22, 2, mChannels);
    writeBytes(header, 24, 4, mSampleRate);
    writeBytes(header, 28, 4, (mSampleRate * mChannels) * 2);
    writeBytes(header, 32, 2, mChannels * 2);
    writeBytes(header, 34, 2, 16);
    writeBytes(header, 40, 4, mRecordDataSize);

    try {
      mBufferOutStream.write(header);
      return true;
    } catch (Throwable e) {
      e.printStackTrace();
      return false;
    }
  }

  private void writeFinal() {
    try {
      if (mRecordFile != null) {
        if (mRandomAccessFile == null) {
          mRandomAccessFile = new RandomAccessFile(mRecordFile, "rw");
        }
        byte[] buffer = new byte[4];
        writeBytes(buffer, 0, 4, mRecordDataSize + 36);
        mRandomAccessFile.seek(4);
        mRandomAccessFile.write(buffer);
        writeBytes(buffer, 0, 4, mRecordDataSize);
        mRandomAccessFile.seek(40);
        mRandomAccessFile.write(buffer);
        mRandomAccessFile.close();
      }
    } catch (Throwable e) {
      e.printStackTrace();
    }
    mRecordBufferData = null;
  }

  private String getFilename() {
    Date now = new Date();
    SimpleDateFormat sdf = new SimpleDateFormat("HHmmss", Locale.getDefault());
    sdf.setTimeZone(TimeZone.getDefault());
    return String.format(Locale.ENGLISH, "FM-%s-%04d.wav", sdf.format(now), mApi.getIntFrequencyKHz() / 100).replace(" ", "0");
  }

  public int getCurrentDuration() {
    return (int) ((System.currentTimeMillis() - mStartTime) / 1000);
  }
}