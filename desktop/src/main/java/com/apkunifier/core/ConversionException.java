package com.apkunifier.core;

/** 转换失败时抛出，携带面向用户的中文原因。 */
public class ConversionException extends Exception {

    public ConversionException(String message) {
        super(message);
    }

    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
