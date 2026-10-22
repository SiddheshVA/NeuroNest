package com.neuronest.backend.admin;

/** 404 from the admin booking actions for an unknown request id. */
public class AdminBookingNotFoundException extends RuntimeException {

    public AdminBookingNotFoundException() {
        super("This booking request was not found.");
    }
}
