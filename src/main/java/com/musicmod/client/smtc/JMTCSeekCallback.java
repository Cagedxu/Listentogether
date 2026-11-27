package com.musicmod.client.smtc;

/**
 * Interface for seek lambda callback.
 */
public interface JMTCSeekCallback {
    /**
     * The callback function.
     * @param position The position that was seeked to in ms.
     */
    void callback(Long position);
}
