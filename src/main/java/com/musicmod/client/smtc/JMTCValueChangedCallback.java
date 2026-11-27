package com.musicmod.client.smtc;

/**
 * Interface for callback when value of property changes.
 * @param <T> the Type of value that changed
 */
public interface JMTCValueChangedCallback<T> {

    /**
     * Callback for when property changes
     * @param property the property that changed
     */
    void callback(T property);

}
