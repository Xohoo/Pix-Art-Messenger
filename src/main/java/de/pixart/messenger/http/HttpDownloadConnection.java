package de.pixart.messenger.http;

import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.entities.DownloadableFile;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.entities.Transferable;
import de.pixart.messenger.entities.TransferablePlaceholder;
import de.pixart.messenger.persistance.FileBackend;
import de.pixart.messenger.services.AbstractConnectionManager;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.FileWriterException;

public class HttpDownloadConnection implements Transferable {

    private HttpConnectionManager mHttpConnectionManager;
    private XmppConnectionService mXmppConnectionService;

    private URL mUrl;
    private Message message;
    private DownloadableFile file;
    private int mStatus = Transferable.STATUS_UNKNOWN;
    private boolean acceptedAutomatically = false;
    private int mProgress = 0;
    private boolean mUseTor = false;
    private boolean canceled = false;

    private final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US);

    public HttpDownloadConnection(HttpConnectionManager manager) {
        this.mHttpConnectionManager = manager;
        this.mXmppConnectionService = manager.getXmppConnectionService();
        this.mUseTor = mXmppConnectionService.useTorToConnect();
    }

    @Override
    public boolean start() {
        if (mXmppConnectionService.hasInternetConnection()) {
            if (this.mStatus == STATUS_OFFER_CHECK_FILESIZE) {
                checkFileSize(true);
            } else {
                new Thread(new FileDownloader(true)).start();
            }
            return true;
        } else {
            return false;
        }
    }

    public void init(Message message) {
        init(message, false);
    }

    public void init(Message message, boolean interactive) {
        this.message = message;
        this.message.setTransferable(this);
        try {
            if (message.hasFileOnRemoteHost()) {
                mUrl = CryptoHelper.toHttpsUrl(message.getFileParams().url);
            } else {
                mUrl = CryptoHelper.toHttpsUrl(new URL(message.getBody().split("\n")[0]));
            }
            String[] parts = mUrl.getPath().toLowerCase().split("\\.");
            String lastPart = parts.length >= 1 ? parts[parts.length - 1] : null;
            String secondToLast = parts.length >= 2 ? parts[parts.length - 2] : null;
            if ("pgp".equals(lastPart) || "gpg".equals(lastPart)) {
                this.message.setEncryption(Message.ENCRYPTION_PGP);
            } else if (message.getEncryption() != Message.ENCRYPTION_OTR
                    && message.getEncryption() != Message.ENCRYPTION_AXOLOTL) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            String extension;
            if (VALID_CRYPTO_EXTENSIONS.contains(lastPart)) {
                extension = secondToLast;
            } else {
                extension = lastPart;
            }
            message.setRelativeFilePath(fileDateFormat.format(new Date(message.getTimeSent())) + "_" + message.getUuid().substring(0, 4) + (extension != null ? ("." + extension) : ""));
            this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
            final String reference = mUrl.getRef();
            if (reference != null && AesGcmURLStreamHandler.IV_KEY.matcher(reference).matches()) {
                this.file.setKeyAndIv(CryptoHelper.hexToBytes(reference));
            }

            if ((this.message.getEncryption() == Message.ENCRYPTION_OTR
                    || this.message.getEncryption() == Message.ENCRYPTION_AXOLOTL)
                    && this.file.getKey() == null) {
                this.message.setEncryption(Message.ENCRYPTION_NONE);
            }
            checkFileSize(interactive);
        } catch (MalformedURLException e) {
            this.cancel();
        }
    }

    private void checkFileSize(boolean interactive) {
        new Thread(new FileSizeChecker(interactive)).start();
    }

    @Override
    public void cancel() {
        this.canceled = true;
        mHttpConnectionManager.finishConnection(this);
        if (message.isFileOrImage()) {
            message.setTransferable(new TransferablePlaceholder(Transferable.STATUS_DELETED));
        } else {
            message.setTransferable(null);
        }
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void finish() {
        mXmppConnectionService.getFileBackend().updateMediaScanner(file);
        message.setTransferable(null);
        mHttpConnectionManager.finishConnection(this);
        boolean notify = acceptedAutomatically && !message.isRead();
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            notify = message.getConversation().getAccount().getPgpDecryptionService().decrypt(message, notify);
        }
        mHttpConnectionManager.updateConversationUi(true);
        if (notify) {
            mXmppConnectionService.getNotificationService().push(message);
        }
    }

    private void changeStatus(int status) {
        this.mStatus = status;
        mHttpConnectionManager.updateConversationUi(true);
    }

    private void showToastForException(Exception e) {
        if (e instanceof java.net.UnknownHostException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_server_not_found);
        } else if (e instanceof java.net.ConnectException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_connect);
        } else if (e instanceof FileWriterException) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_could_not_write_file);
        } else if (!(e instanceof CancellationException)) {
            mXmppConnectionService.showErrorToastInUi(R.string.download_failed_file_not_found);
        }
    }

    public void updateProgress(long i) {
        this.mProgress = (int) i;
        mHttpConnectionManager.updateConversationUi(false);
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public long getFileSize() {
        if (this.file != null) {
            return this.file.getExpectedSize();
        } else {
            return 0;
        }
    }

    @Override
    public int getProgress() {
        return this.mProgress;
    }

    private class FileSizeChecker implements Runnable {

        private boolean interactive = false;

        public FileSizeChecker(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            long size;
            try {
                size = retrieveFileSize();
            } catch (Exception e) {
                changeStatus(STATUS_OFFER_CHECK_FILESIZE);
                Log.d(Config.LOGTAG, "io exception in http file size checker: " + e.getMessage());
                if (interactive) {
                    showToastForException(e);
                } else {
                    HttpDownloadConnection.this.acceptedAutomatically = false;
                    HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
                }
                cancel();
                return;
            }
            file.setExpectedSize(size);
            message.resetFileParams();
            if (mHttpConnectionManager.hasStoragePermission()
                    && size <= mHttpConnectionManager.getAutoAcceptFileSize()
                    && mXmppConnectionService.isDataSaverDisabled()) {
                HttpDownloadConnection.this.acceptedAutomatically = true;
                new Thread(new FileDownloader(interactive)).start();
            } else {
                changeStatus(STATUS_OFFER);
                HttpDownloadConnection.this.acceptedAutomatically = false;
                HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
            }
        }

        private long retrieveFileSize() throws IOException {
            PowerManager.WakeLock wakeLock = mHttpConnectionManager.createWakeLock("http_download_filesize" + message.getUuid());
            try {
                wakeLock.acquire();
                Log.d(Config.LOGTAG, "retrieve file size. interactive:" + String.valueOf(interactive));
                changeStatus(STATUS_CHECKING);
                HttpURLConnection connection;
                if (mUseTor) {
                    connection = (HttpURLConnection) mUrl.openConnection(mHttpConnectionManager.getProxy());
                } else {
                    connection = (HttpURLConnection) mUrl.openConnection();
                }
                connection.setRequestMethod("HEAD");
                Log.d(Config.LOGTAG, "url: " + connection.getURL().toString());
                Log.d(Config.LOGTAG, "connection: " + connection.toString());
                connection.setRequestProperty("User-Agent", mXmppConnectionService.getIqGenerator().getIdentityName());
                if (connection instanceof HttpsURLConnection) {
                    mHttpConnectionManager.setupTrustManager((HttpsURLConnection) connection, interactive);
                }
                connection.setConnectTimeout(Config.SOCKET_TIMEOUT * 1000);
                connection.setReadTimeout(Config.CONNECT_TIMEOUT * 1000);
                connection.connect();
                String contentLength = connection.getHeaderField("Content-Length");
                connection.disconnect();
                if (contentLength == null) {
                    throw new IOException("no content-length found in HEAD response");
                }
                return Long.parseLong(contentLength, 10);
            } catch (IOException e) {
                Log.d(Config.LOGTAG, "io exception during HEAD " + e.getMessage());
                throw e;
            } catch (NumberFormatException e) {
                throw new IOException();
            } finally {
                wakeLock.release();
            }
        }

    }

    private class FileDownloader implements Runnable {

        private boolean interactive = false;

        private OutputStream os;

        public FileDownloader(boolean interactive) {
            this.interactive = interactive;
        }

        @Override
        public void run() {
            try {
                changeStatus(STATUS_DOWNLOADING);
                download();
                updateImageBounds();
                finish();
            } catch (SSLHandshakeException e) {
                changeStatus(STATUS_OFFER);
            } catch (Exception e) {
                if (interactive) {
                    showToastForException(e);
                } else {
                    HttpDownloadConnection.this.acceptedAutomatically = false;
                    HttpDownloadConnection.this.mXmppConnectionService.getNotificationService().push(message);
                }
                cancel();
            }
        }

        private void download() throws Exception {
            InputStream is = null;
            HttpURLConnection connection = null;
            PowerManager.WakeLock wakeLock = mHttpConnectionManager.createWakeLock("http_download_" + message.getUuid());
            try {
                wakeLock.acquire();
                if (mUseTor) {
                    connection = (HttpURLConnection) mUrl.openConnection(mHttpConnectionManager.getProxy());
                } else {
                    connection = (HttpURLConnection) mUrl.openConnection();
                }
                if (connection instanceof HttpsURLConnection) {
                    mHttpConnectionManager.setupTrustManager((HttpsURLConnection) connection, interactive);
                }
                connection.setRequestProperty("User-Agent", mXmppConnectionService.getIqGenerator().getIdentityName());
                final boolean tryResume = file.exists() && file.getKey() == null && file.getSize() > 0;
                long resumeSize = 0;
                long expected = file.getExpectedSize();
                if (tryResume) {
                    resumeSize = file.getSize();
                    Log.d(Config.LOGTAG, "http download trying resume after" + resumeSize + " of " + expected);
                    connection.setRequestProperty("Range", "bytes=" + resumeSize + "-");
                }
                connection.setConnectTimeout(Config.SOCKET_TIMEOUT * 1000);
                connection.setReadTimeout(Config.CONNECT_TIMEOUT * 1000);
                connection.connect();
                is = new BufferedInputStream(connection.getInputStream());
                final String contentRange = connection.getHeaderField("Content-Range");
                boolean serverResumed = tryResume && contentRange != null && contentRange.startsWith("bytes "+resumeSize+"-");
                long transmitted = 0;
                if (tryResume && serverResumed) {
                    Log.d(Config.LOGTAG, "server resumed");
                    transmitted = file.getSize();
                    updateProgress(Math.round(((double) transmitted / expected) * 100));
                    os = AbstractConnectionManager.createAppendedOutputStream(file);
                    if (os == null) {
                        throw new FileWriterException();
                    }
                } else {
                    long reportedContentLengthOnGet;
                    try {
                        reportedContentLengthOnGet = Long.parseLong(connection.getHeaderField("Content-Length"));
                    } catch (NumberFormatException | NullPointerException e) {
                        reportedContentLengthOnGet = 0;
                    }
                    if (expected != reportedContentLengthOnGet) {
                        Log.d(Config.LOGTAG, "content-length reported on GET (" + reportedContentLengthOnGet + ") did not match Content-Length reported on HEAD (" + expected + ")");
                    }
                    file.getParentFile().mkdirs();
                    if (!file.exists() && !file.createNewFile()) {
                        throw new FileWriterException();
                    }
                    os = AbstractConnectionManager.createOutputStream(file, true);
                }
                int count;
                byte[] buffer = new byte[4096];
                while ((count = is.read(buffer)) != -1) {
                    transmitted += count;
                    try {
                        os.write(buffer, 0, count);
                    } catch (IOException e) {
                        throw new FileWriterException();
                    }
                    updateProgress(Math.round(((double) transmitted / expected) * 100));
                    if (canceled) {
                        throw new CancellationException();
                    }
                }
                try {
                    os.flush();
                } catch (IOException e) {
                    throw new FileWriterException();
                }
            } catch (CancellationException | IOException e) {
                Log.d(Config.LOGTAG, "http download failed " + e.getMessage());
                throw e;
            } finally {
                FileBackend.close(os);
                FileBackend.close(is);
                if (connection != null) {
                    connection.disconnect();
                }
                wakeLock.release();
            }
        }

        private void updateImageBounds() {
            message.setType(Message.TYPE_FILE);
            final URL url;
            final String ref = mUrl.getRef();
            if (ref != null && AesGcmURLStreamHandler.IV_KEY.matcher(ref).matches()) {
                url = CryptoHelper.toAesGcmUrl(mUrl);
            } else {
                url = mUrl;
            }
            mXmppConnectionService.getFileBackend().updateFileParams(message, url);
            mXmppConnectionService.updateMessage(message);
        }

    }
}
