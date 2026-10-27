package com.zoolcoder.tv.calendar;

/** A request the server refuses; the status is what the phone gets back. */
final class HttpException extends Exception {
    final int status;

    HttpException(int status, String message) {
        super(message);
        this.status = status;
    }
}
