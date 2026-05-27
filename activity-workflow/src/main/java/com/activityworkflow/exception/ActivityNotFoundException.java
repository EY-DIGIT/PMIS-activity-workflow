package com.activityworkflow.exception;

public class ActivityNotFoundException extends RuntimeException {

    public ActivityNotFoundException(String message) {
        super(message);
    }
}
